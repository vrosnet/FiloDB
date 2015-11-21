package filodb.core.reprojector

import java.nio.ByteBuffer

import filodb.core.metadata._
import org.velvia.filo.{RowReader, RowToVectorBuilder}


trait Reprojector {

  def project(projection: Projection,
              rows: Seq[RowReader]): Iterator[(Any, Seq[FlushedChunk])]
}

object Reprojector extends Reprojector {


  override def project(projection: Projection, rows: Seq[RowReader]): Iterator[(Any, Seq[FlushedChunk])] = {
    // lets group rows within partition by segment
    import filodb.core.util.Iterators._
    val partitionedRows = rows.iterator.sortedGroupBy(projection.partitionFunction)
    partitionedRows.map { case (partitionKey, partRows) =>

      val segmentedRows = partRows.sortedGroupBy(projection.segmentFunction)
      val segmentChunks = segmentedRows.map { case (segment, segmentRowsIter) =>
        val segmentRows = segmentRowsIter.toSeq
        // For each segment grouping of rows... set up a SegmentInfo
        val segmentInfo = DefaultSegmentInfo(projection.dataset, partitionKey, segment, projection)
        // within a segment we sort rows by sort order
        implicit val ordering = projection.sortType.ordering
        val rows = segmentRows.sortBy(projection.sortFunction)
        val sortKeyRange = KeyRange(
          projection.sortFunction(segmentRows.head),
          projection.sortFunction(segmentRows.last)
        )

        // then write the rows as a chunk to the segment
        val columnVectorMap = RowToVectorBuilder.buildFromRows(rows.iterator, projection.filoSchema)
        val columnVectors = new Array[ByteBuffer](projection.schema.length)
        projection.schema.zipWithIndex.foreach { case (c, i) => columnVectors(i) = columnVectorMap(c.name) }
        // we also separate the keys for summarizing
        val keys = rows.map(i => projection.keyFunction(i))
        FlushedChunk(segmentInfo, keys, sortKeyRange, columnVectors)
      }.toSeq
      (partitionKey, segmentChunks)
    }
  }


}
