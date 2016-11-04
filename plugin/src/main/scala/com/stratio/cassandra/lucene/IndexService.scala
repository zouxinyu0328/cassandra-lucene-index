/*
 * Copyright (C) 2014 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.stratio.cassandra.lucene

import java.lang.management.ManagementFactory
import javax.management.{JMException, ObjectName}

import com.stratio.cassandra.lucene.IndexService._
import com.stratio.cassandra.lucene.column.Columns
import com.stratio.cassandra.lucene.index.{DocumentIterator, FSIndex}
import com.stratio.cassandra.lucene.mapping.{ExpressionMapper, PartitionMapper, TokenMapper}
import com.stratio.cassandra.lucene.search.Search
import com.stratio.cassandra.lucene.util._
import org.apache.cassandra.config.ColumnDefinition
import org.apache.cassandra.db._
import org.apache.cassandra.db.filter._
import org.apache.cassandra.db.partitions._
import org.apache.cassandra.db.rows._
import org.apache.cassandra.index.transactions.IndexTransaction
import org.apache.cassandra.schema.IndexMetadata
import org.apache.cassandra.utils.concurrent.OpOrder
import org.apache.lucene.document.Document
import org.apache.lucene.index.{IndexableField, Term}
import org.apache.lucene.search.{Query, Sort, SortField}
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._
import scala.collection.mutable

/** Lucene index service provider.
  *
  * @param table         the indexed table
  * @param indexMetadata the index metadata
  * @author Andres de la Pena `adelapena@stratio.com`
  */
abstract class IndexService(val table: ColumnFamilyStore, val indexMetadata: IndexMetadata)
  extends IndexServiceMBean {

  val metadata = table.metadata
  val ksName = metadata.ksName
  val cfName = metadata.cfName
  val idxName = indexMetadata.name
  val qualifiedName = s"$ksName.$cfName.$idxName"

  // Parse options
  val options = new IndexOptions(metadata, indexMetadata)

  // Setup mapping
  val schema = options.schema
  val tokenMapper = new TokenMapper
  val partitionMapper = new PartitionMapper(metadata)
  val expressionMapper = ExpressionMapper(metadata, indexMetadata)
  val regulars = metadata.partitionColumns.regulars.toSet
  val mappedRegulars = regulars.map(_.name.toString).filter(schema.mappedCells.contains)
  val mapsMultiCell = regulars.exists(x => x.`type`.isMultiCell && schema.mapsCell(x.name.toString))

  // Setup FS index and write queue
  val queue = TaskQueue.build(options.indexingThreads, options.indexingQueuesSize)
  val lucene = new FSIndex(
    idxName,
    options.path,
    options.schema.analyzer(),
    options.refreshSeconds,
    options.ramBufferMB,
    options.maxMergeMB,
    options.maxCachedMB)

  // Delay JMX MBean creation
  var mBean: ObjectName = _

  def init() {

    // Initialize index
    try {
      val sort = new Sort(keySortFields.toArray: _*)
      lucene.init(sort, fieldsToLoad)
    } catch {
      case e: Exception =>
        logger.error(s"Initialization of Lucene FS directory for index '$idxName' has failed", e)
    }

    // Register JMX MBean
    try {
      val mBeanName = "com.stratio.cassandra.lucene:type=Lucene," +
        s"keyspace=$ksName,table=$cfName,index=$idxName"
      mBean = new ObjectName(mBeanName)
      ManagementFactory.getPlatformMBeanServer.registerMBean(this, this.mBean)
    } catch {
      case e: JMException => logger.error("Error while registering Lucene index JMX MBean", e)
    }
  }

  /** Returns the Lucene [[SortField]]s required to retrieve documents sorted by primary key.
    *
    * @return the sort fields
    */
  def keySortFields: List[SortField]

  /** Returns the names of the Lucene fields to be loaded from index during searches.
    *
    * @return the names of the fields to be loaded
    */
  def fieldsToLoad: Set[String]

  /** Returns a [[Columns]] representing the specified row.
    *
    * @param key the partition key
    * @param row the row
    * @return the columns representing the specified row
    */
  def columns(key: DecoratedKey, row: Row): Columns

  def keyIndexableFields(key: DecoratedKey, row: Row): List[IndexableField]

  /** Returns if the specified column definition is mapped by this index.
    *
    * @param columnDef a column definition
    * @return `true` if the column is mapped, `false` otherwise
    */
  def dependsOn(columnDef: ColumnDefinition): Boolean = {
    schema.mapsCell(columnDef.name.toString)
  }

  /** Returns the validated search contained in the specified expression.
    *
    * @param expression a custom CQL expression
    * @return the validated expression
    */
  def validate(expression: RowFilter.Expression): Search = {
    expressionMapper.search(expression).validate(schema)
  }

  /** Returns a Lucene term uniquely identifying the specified row.
    *
    * @param key the partition key
    * @param row the row
    * @return a Lucene identifying term
    */
  def term(key: DecoratedKey, row: Row): Term

  /** Returns a Lucene term identifying documents representing all the row's which are in the
    * partition the specified [[DecoratedKey]].
    *
    * @param key the partition key
    * @return a Lucene term representing `key`
    */
  def term(key: DecoratedKey): Term = {
    partitionMapper.term(key)
  }

  /** Returns if SSTables can contain additional columns of the specified row so read-before-write
    * is required prior to indexing.
    *
    * @param key the partition key
    * @param row the row
    * @return `true` if read-before-write is required, `false` otherwise
    */
  def needsReadBeforeWrite(key: DecoratedKey, row: Row): Boolean = {
    mapsMultiCell || !mappedRegulars.subsetOf(row.columns.map(_.name.toString).toSet)
  }

  /** Returns the [[DecoratedKey]] contained in the specified Lucene document.
    *
    * @param document the document containing the partition key to be get
    * @return the partition key contained in the specified Lucene document
    */
  def decoratedKey(document: Document): DecoratedKey = {
    partitionMapper.decoratedKey(document)
  }

  /** Creates an new [[IndexWriter]] object for updates to a given partition.
    *
    * @param key             key of the partition being modified
    * @param nowInSec        current time of the update operation
    * @param orderGroup      operation group spanning the update operation
    * @param transactionType what kind of update is being performed on the base data
    * @return the newly created index writer
    */
  def writer(
      key: DecoratedKey,
      nowInSec: Int,
      orderGroup: OpOrder.Group,
      transactionType: IndexTransaction.Type): IndexWriter

  /** Deletes all the index contents. */
  def truncate() {
    queue.submitSynchronous(lucene.truncate)
  }

  /** Closes and removes all the index files. */
  def delete() {
    try {
      queue.close()
      ManagementFactory.getPlatformMBeanServer.unregisterMBean(mBean)
    } catch {
      case e: JMException => logger.error("Error while unregistering Lucene index MBean", e)
    } finally {
      lucene.delete()
    }
  }

  /** Upserts the specified row.
    *
    * @param key      the partition key
    * @param row      the row to be upserted
    * @param nowInSec now in seconds
    */
  def upsert(key: DecoratedKey, row: Row, nowInSec: Int) {
    queue.submitAsynchronous(
      key, () => {
        val t = term(key, row)
        val cols = columns(key, row).withoutDeleted(nowInSec)
        val fields = schema.indexableFields(cols)
        if (fields.isEmpty) {
          lucene.delete(t)
        } else {
          val doc = new Document()
          keyIndexableFields(key, row).foreach(doc.add)
          fields.foreach(doc.add)
          lucene.upsert(t, doc)
        }
      })
  }

  /** Deletes the partition identified by the specified key.
    *
    * @param key the partition key
    * @param row the row to be deleted
    */
  def delete(key: DecoratedKey, row: Row) {
    queue.submitAsynchronous(key, () => lucene.delete(term(key, row)))
  }

  /** Deletes the partition identified by the specified key.
    *
    * @param key the partition key
    */
  def delete(key: DecoratedKey) {
    queue.submitAsynchronous(key, () => lucene.delete(term(key)))
  }

  /** Returns a new index searcher for the specified read command.
    *
    * @param command the read command being executed
    * @return a searcher with which to perform the supplied command
    */
  def search(command: ReadCommand, orderGroup: ReadOrderGroup): UnfilteredPartitionIterator = {

    // Parse search
    Tracer.trace("Building Lucene search")
    val search = expressionMapper.search(command)
    val q = search.query(schema, query(command).orNull)
    val a = after(search.paging, command)
    val s = sort(search)
    val n = command.limits.count

    // Refresh if required
    if (search.refresh) {
      Tracer.trace("Refreshing Lucene index searcher")
      refresh()
    }

    // Search
    Tracer.trace(s"Lucene index searching for $n rows")
    val documents = lucene.search(a, q, s, n)
    reader(documents, command, orderGroup)
  }

  /** Returns the key range query represented by the specified read command.
    *
    * @param command the read command
    * @return the key range query
    */
  def query(command: ReadCommand): Option[Query] = command match {
    case command: SinglePartitionReadCommand =>
      val key = command.partitionKey
      val filter = command.clusteringIndexFilter(key)
      Some(query(key, filter))
    case command: PartitionRangeReadCommand => query(command.dataRange)
    case _ => throw new IndexException(s"Unsupported read command ${command.getClass}")
  }

  /** Returns a query to get the documents satisfying the specified key and clustering filter.
    *
    * @param key    the partition key
    * @param filter the clustering key range
    * @return a query to get the documents satisfying the key range
    */
  def query(key: DecoratedKey, filter: ClusteringIndexFilter): Query

  /** Returns a query to get the documents satisfying the specified data range.
    *
    * @param dataRange the data range
    * @return a query to get the documents satisfying the data range
    */
  def query(dataRange: DataRange): Option[Query]

  def after(pagingState: IndexPagingState, command: ReadCommand): Option[Term] = {
    if (pagingState == null) return None
    pagingState.forCommand(command).map { case (key, clustering) => after(key, clustering) }
  }

  /** Returns a Lucene query to retrieve the row identified by the specified paging state.
    *
    * @param key        the partition key
    * @param clustering the clustering key
    * @return the query to retrieve the row
    */
  def after(key: DecoratedKey, clustering: Clustering): Term

  /** Returns the Lucene sort with the specified search sorting requirements followed by the
    * Cassandra's natural ordering based on partitioning token and cell name.
    *
    * @param search the search containing sorting requirements
    * @return a Lucene sort according to `search`
    */
  def sort(search: Search): Sort = {
    val sortFields = mutable.ListBuffer[SortField]()
    if (search.usesSorting) {
      sortFields ++= search.sortFields(schema)
    }
    if (search.usesRelevance) {
      sortFields += SortField.FIELD_SCORE
    }
    sortFields ++= keySortFields
    new Sort(sortFields.toArray: _*)
  }

  /** Reads from the local SSTables the rows identified by the specified search.
    *
    * @param documents  the Lucene documents
    * @param command    the Cassandra command
    * @param orderGroup the Cassandra read order group
    * @return the local rows satisfying the search
    */
  def reader(
      documents: DocumentIterator,
      command: ReadCommand,
      orderGroup: ReadOrderGroup): IndexReader
  /** Ensures that values present in a partition update are valid according to the schema.
    *
    * @param update the partition update containing the values to be validated
    */
  def validate(update: PartitionUpdate) {
    val key = update.partitionKey
    update.foreach(row => schema.validate(columns(key, row)))
  }

  /** @inheritdoc */
  override def commit() {
    queue.submitSynchronous(lucene.commit)
  }

  /** @inheritdoc */
  override def getNumDocs: Int = {
    lucene.getNumDocs
  }

  /** @inheritdoc */
  override def getNumDeletedDocs: Int = {
    lucene.getNumDeletedDocs
  }

  /** @inheritdoc */
  override def forceMerge(maxNumSegments: Int, doWait: Boolean) {
    queue.submitSynchronous(() => lucene.forceMerge(maxNumSegments, doWait))
  }

  /** @inheritdoc */
  override def forceMergeDeletes(doWait: Boolean) {
    queue.submitSynchronous(() => lucene.forceMergeDeletes(doWait))
  }

  /** @inheritdoc */
  override def refresh() {
    queue.submitSynchronous(lucene.refresh)
  }

}

object IndexService {

  val logger = LoggerFactory.getLogger(classOf[IndexService])

  /** Returns a new index service for the specified indexed table and index metadata.
    *
    * @param table         the indexed table
    * @param indexMetadata the index metadata
    * @return the index service
    */
  def build(table: ColumnFamilyStore, indexMetadata: IndexMetadata): IndexService = {
    if (table.getComparator.subtypes.isEmpty) {
      new IndexServiceSkinny(table, indexMetadata)
    } else {
      new IndexServiceWide(table, indexMetadata)
    }
  }

}