package com.datastax.spark.connector.rdd.partitioner

import java.net.InetAddress

import scala.collection.JavaConversions._
import scala.collection.parallel.ForkJoinTaskSupport
import scala.concurrent.forkjoin.ForkJoinPool
import org.apache.spark.Partition
import org.apache.spark.Partitioner
import com.datastax.driver.core.{Metadata, TokenRange => DriverTokenRange}
import com.datastax.spark.connector.PartitionKeyColumns
import com.datastax.spark.connector.cql.{CassandraConnector, TableDef}
import com.datastax.spark.connector.rdd._
import com.datastax.spark.connector.rdd.partitioner.dht.{Token, TokenFactory}
import com.datastax.spark.connector.util.Quote._
import com.datastax.spark.connector.writer.{RowWriterFactory}

import scala.reflect.ClassTag

class CassandraRDDPartitioner[Key : ClassTag, V](
  val partitions: Seq[CassandraPartition],
  tableDef: TableDef,
  connector: CassandraConnector,
  tokenBounds: (Token[V], Token[V]))(
implicit
  rwf:RowWriterFactory[Key]) extends Partitioner {

  implicit val tokenOrdering = tokenBounds._1.ord

  val extractedPartitions = partitions
    .map(cqlPartition => (cqlPartition.index, cqlPartition.tokenRanges))

  val (minToken, maxToken) = tokenBounds

  lazy val tokenGenerator = {
    val partitionKeyWriter = implicitly[RowWriterFactory[Key]]
      .rowWriter (tableDef, PartitionKeyColumns.selectFrom(tableDef))

    new TokenGenerator(connector, tableDef, partitionKeyWriter)
  }

  override def getPartition(key: Any): Int = {
    key match {
      case x: Key => {
        val token = tokenGenerator.getTokenFor(x)
        indexOfPartitionContaining(token)
      }
      case other => throw new IllegalArgumentException(s"Couldn't determine the key from object $other")
    }
  }

  def indexOfPartitionContaining(token: com.datastax.driver.core.Token): Int = {
    val tokenValue = token.getValue.asInstanceOf[V]
    val minTokenValue = minToken
    val maxTokenValue = maxToken

    (tokenValue) < (minTokenValue.value)
    1
    /*
    partitions.find { case (index, ranges) =>
      ranges.exists{ case (start, end, wrap) =>
          if (end == minTokenValue && start < tokenValue) {
            true
          } else if ( start == minTokenValue && end >= tokenValue) {
            true
          } else if ( !wrap && start < tokenValue && end >= tokenValue) {
            true
          } else if ( wrap && start < tokenValue || end > tokenValue) {
            true
          } else false
      }
    }.get._1
    */
  }

  override def numPartitions: Int = partitions.length

}
/** Creates CassandraPartitions for given Cassandra table */
class CassandraRDDPartitionGenerator[V, T <: Token[V]](
    connector: CassandraConnector,
    val tableDef: TableDef,
    splitCount: Option[Int],
    splitSize: Long)(
  implicit
    tokenFactory: TokenFactory[V, T]){

  type Token = com.datastax.spark.connector.rdd.partitioner.dht.Token[T]
  type TokenRange = com.datastax.spark.connector.rdd.partitioner.dht.TokenRange[V, T]

  private val keyspaceName = tableDef.keyspaceName
  private val tableName = tableDef.tableName

  private val totalDataSize: Long = {
    // If we know both the splitCount and splitSize, we should pretend the total size of the data is
    // their multiplication. TokenRangeSplitter will try to produce splits of desired size, and this way
    // their number will be close to desired splitCount. Otherwise, if splitCount is not set,
    // we just go to C* and read the estimated data size from an appropriate system table
    splitCount match {
      case Some(c) => c * splitSize
      case None => new DataSizeEstimates(connector, keyspaceName, tableName).dataSizeInBytes
    }
  }

  def tokenRange(range: DriverTokenRange, metadata: Metadata): TokenRange = {
    val startToken = tokenFactory.tokenFromString(range.getStart.getValue.toString)
    val endToken = tokenFactory.tokenFromString(range.getEnd.getValue.toString)
    val replicas = metadata.getReplicas(Metadata.quote(keyspaceName), range).map(_.getAddress).toSet
    val dataSize = (tokenFactory.ringFraction(startToken, endToken) * totalDataSize).toLong
    new TokenRange(startToken, endToken, replicas, dataSize)
  }

  def fullRange: TokenRange = {
    new TokenRange(tokenFactory.minToken, tokenFactory.maxToken, Set.empty, 0)
  }

  private def describeRing: Seq[TokenRange] = {
    connector.withClusterDo { cluster =>
      val metadata = cluster.getMetadata
      for (tr <- metadata.getTokenRanges.toSeq) yield tokenRange(tr, metadata)
    }
  }

  private def splitsOf(
      tokenRanges: Iterable[TokenRange],
      splitter: TokenRangeSplitter[V, T]): Iterable[TokenRange] = {

    val parTokenRanges = tokenRanges.par
    parTokenRanges.tasksupport = new ForkJoinTaskSupport(CassandraRDDPartitionGenerator.pool)
    (for (tokenRange <- parTokenRanges;
          split <- splitter.split(tokenRange, splitSize)) yield split).seq
  }

  private def splitToCqlClause(range: TokenRange): Iterable[CqlTokenRange] = {
    val startToken = range.start.value
    val endToken = range.end.value
    val wrapAround = range.isWrapAround
    val pk = tableDef.partitionKey.map(_.columnName).map(quote).mkString(", ")

    if (range.end == tokenFactory.minToken)
      List(CqlTokenRange(s"token($pk) > ?", wrapAround, startToken))
    else if (range.start == tokenFactory.minToken)
      List(CqlTokenRange(s"token($pk) <= ?", wrapAround, endToken))
    else if (!range.isWrapAround)
      List(CqlTokenRange(s"token($pk) > ? AND token($pk) <= ?", wrapAround, startToken, endToken))
    else
      List(
        CqlTokenRange(s"token($pk) > ?", wrapAround, startToken),
        CqlTokenRange(s"token($pk) <= ?", wrapAround, endToken))
  }

  private def createTokenRangeSplitter: TokenRangeSplitter[V, T] = {
    tokenFactory.asInstanceOf[TokenFactory[_, _]] match {
      case TokenFactory.RandomPartitionerTokenFactory =>
        new RandomPartitionerTokenRangeSplitter(totalDataSize).asInstanceOf[TokenRangeSplitter[V, T]]
      case TokenFactory.Murmur3TokenFactory =>
        new Murmur3PartitionerTokenRangeSplitter(totalDataSize).asInstanceOf[TokenRangeSplitter[V, T]]
      case _ =>
        throw new UnsupportedOperationException(s"Unsupported TokenFactory $tokenFactory")
    }
  }

  /**
    * Generates a list of the CassandraPartitions to be used in the TableScanRDD and a list
    * of the TokenRanges used to craft those partitions along with their indexes.
    */
  lazy val partitions: Seq[CassandraPartition] = {

    val tokenRanges = splitCount match {
      case Some(1) => Seq(fullRange)
      case _ => describeRing
    }

    val endpointCount = tokenRanges.map(_.replicas).reduce(_ ++ _).size
    val splitter = createTokenRangeSplitter
    val splits = splitsOf(tokenRanges, splitter).toSeq
    val maxGroupSize = tokenRanges.size / endpointCount
    val clusterer = new TokenRangeClusterer[V, T](splitSize, maxGroupSize)
    val tokenRangeGroups = clusterer.group(splits).toArray

    val tokenGroupsWithMetadata = for (group <- tokenRangeGroups) yield {
      val replicas = group.map(_.replicas).reduce(_ intersect _)
      val rowCount = group.map(_.dataSize).sum
      (replicas, rowCount, group)
    }

    val sortedGroups = tokenGroupsWithMetadata
      .sortBy { case (replicas, rowCount, group) => (replicas.size, -rowCount) }
      .zipWithIndex

    for (((replicas, rowCount, group), index) <- sortedGroups) yield {
      val cqlPredicates = group.flatMap(splitToCqlClause)
      CassandraPartition(index, replicas, cqlPredicates, rowCount)
    }
  }

  def getPartitioner[Key: ClassTag]()(
    implicit rowWriterFactory: RowWriterFactory[Key]) : CassandraRDDPartitioner[Key, V] = {
    new CassandraRDDPartitioner[Key, V](
      partitions,
      tableDef,
      connector,
      (tokenFactory.minToken, tokenFactory.maxToken))
  }

}

object CassandraRDDPartitionGenerator {
  /** Affects how many concurrent threads are used to fetch split information from cassandra nodes, in `getPartitions`.
    * Does not affect how many Spark threads fetch data from Cassandra. */
  val MaxParallelism = 16

  /** How many token ranges to sample in order to estimate average number of rows per token */
  val TokenRangeSampleSize = 16

  private val pool: ForkJoinPool = new ForkJoinPool(MaxParallelism)

  type V = t forSome { type t }
  type T = t forSome { type t <: Token[V] }

  /** Creates a `CassandraRDDPartitionGenerator` for the given cluster and table.
    * Unlike the class constructor, this method does not take the generic `V` and `T` parameters,
    * and therefore you don't need to specify the ones proper for the partitioner used in the
    * Cassandra cluster. */
  def apply(
    conn: CassandraConnector,
    tableDef: TableDef,
    splitCount: Option[Int],
    splitSize: Int): CassandraRDDPartitionGenerator[V, T] = {

    val tokenFactory = getTokenFactory(conn)
    new CassandraRDDPartitionGenerator(conn, tableDef, splitCount, splitSize)(tokenFactory)
  }

  def getTokenFactory(conn: CassandraConnector) : TokenFactory[V, T] = {
    val partitionerName = conn.withSessionDo { session =>
      session.execute("SELECT partitioner FROM system.local").one().getString(0)
    }
    TokenFactory.forCassandraPartitioner(partitionerName)
  }
}
