package filodb.core.store

import com.typesafe.scalalogging.slf4j.StrictLogging
import java.util.concurrent.ConcurrentSkipListMap
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

import filodb.core._
import filodb.core.metadata.{Column, DataColumn, Dataset}

/**
 * An in-memory MetaStore.  Does not aim to keep data distributed, but is just a
 * single-node implementation, which works for example in the Spark driver since
 * the MetaStore is only called by the driver code and not by workers.
 *
 * NOTE: the database name is ignored by the InMemoryMetaStore.
 */
class InMemoryMetaStore(implicit val ec: ExecutionContext) extends MetaStore with StrictLogging {
  import collection.JavaConverters._

  logger.info("Starting InMemoryMetaStore...")

  val datasets = new TrieMap[String, Dataset]
  type ColumnMap = ConcurrentSkipListMap[(Int, Types.ColumnId), DataColumn]
  val colMapOrdering = math.Ordering[(Int, Types.ColumnId)]
  val columns = new TrieMap[String, ColumnMap]

  def initialize(database: String): Future[Response] = Future.successful(Success)

  def clearAllData(database: String): Future[Response] = Future {
    logger.warn("Clearing all data!")
    datasets.clear()
    columns.clear()
    Success
  }

  /**
   * ** Dataset API ***
   */

  def newDataset(dataset: Dataset, database: Option[String] = None): Future[Response] = {
    if (dataset.projections.isEmpty) {
      Future.failed(MetadataException(new IllegalArgumentException(s"Dataset $dataset has no projections")))
    } else {
      datasets.putIfAbsent(dataset.name, dataset) match {
        case None    => Future.successful(Success)
        case Some(x) =>
          logger.info(s"Ignoring newDataset($dataset); entry already exists")
          Future.successful(AlreadyExists)
      }
    }
  }

  def getDataset(ref: DatasetRef): Future[Dataset] =
    datasets.get(ref.dataset).map(Future.successful)
            .getOrElse(Future.failed(NotFoundError(ref.dataset)))

  def getAllDatasets(database: String): Future[Seq[String]] = Future.successful(datasets.keys.toSeq)

  def deleteDataset(ref: DatasetRef): Future[Response] = Future {
    datasets.remove(ref.dataset)
    columns.remove(ref.dataset)
    Success
  }

  /**
   * ** Column API ***
   */

  def insertColumn(column: DataColumn, ref: DatasetRef): Future[Response] = {
    // See https://issues.scala-lang.org/browse/SI-7943
    val columnMap = columns.get(ref.dataset) match {
      case Some(cMap) => cMap
      case None =>
        val newCMap = new ColumnMap(colMapOrdering)
        columns.putIfAbsent(ref.dataset, newCMap).getOrElse(newCMap)
    }
    val oldVal = columnMap.putIfAbsent((column.version, column.name), column)
    // If oldVal is null then it was absent and write worked.
    Future.successful(Option(oldVal).map(x => NotApplied).getOrElse(Success))
  }

  def getSchema(dataset: DatasetRef, version: Int): Future[Column.Schema] = Future {
    columns.get(dataset.dataset).map { columnMap =>
      columnMap.entrySet.asScala
               .takeWhile(_.getKey()._1 <= version)
               .map(_.getValue)
               .foldLeft(Column.EmptySchema)(Column.schemaFold)
    }.getOrElse(Column.EmptySchema)
  }

  def shutdown(): Unit = {}
}