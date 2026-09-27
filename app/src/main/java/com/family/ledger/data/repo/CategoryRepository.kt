package com.family.ledger.data.repo

import com.family.ledger.core.Ids
import androidx.room.withTransaction
import com.family.ledger.data.db.AppDatabase
import com.family.ledger.data.db.entity.CategoryEntity
import com.family.ledger.data.db.entity.TagEntity
import com.family.ledger.data.sync.SyncJournal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 一级分类 + 其二级分类。 */
data class CategoryGroup(
    val top: CategoryEntity,
    val children: List<CategoryEntity>,
)

class CategoryRepository(
    private val db: AppDatabase,
    private val journal: SyncJournal,
) {
    private val categoryDao get() = db.categoryDao()
    private val tagDao get() = db.tagDao()

    fun observeAll(): Flow<List<CategoryEntity>> = categoryDao.observeAll()

    fun observeGroups(kind: String): Flow<List<CategoryGroup>> =
        categoryDao.observeAll().map { all -> buildGroups(all, kind) }

    suspend fun groups(kind: String): List<CategoryGroup> = buildGroups(categoryDao.all(), kind)

    private fun buildGroups(all: List<CategoryEntity>, kind: String): List<CategoryGroup> {
        val tops = all.filter { it.kind == kind && it.parentId == null && !it.archived }
            .sortedWith(compareBy({ it.sortOrder }, { it.name }))
        val byParent = all.filter { it.parentId != null && !it.archived }.groupBy { it.parentId }
        return tops.map { top ->
            CategoryGroup(
                top = top,
                children = (byParent[top.id] ?: emptyList())
                    .sortedWith(compareBy({ it.sortOrder }, { it.name })),
            )
        }
    }

    suspend fun createCategory(name: String, kind: String, parentId: String? = null): CategoryEntity = db.withTransaction {
        val now = System.currentTimeMillis()
        val maxOrder = categoryDao.all()
            .filter { it.parentId == parentId && it.kind == kind }
            .maxOfOrNull { it.sortOrder } ?: -1
        val c = CategoryEntity(
            id = Ids.newId("c-"),
            name = name,
            parentId = parentId,
            kind = kind,
            sortOrder = maxOrder + 1,
            updatedAt = now,
        )
        categoryDao.upsert(c)
        journal.record(c)
        c
    }

    suspend fun renameCategory(id: String, newName: String) = db.withTransaction {
        val all = categoryDao.all()
        val c = all.firstOrNull { it.id == id } ?: return@withTransaction
        val updated = c.copy(name = newName, updatedAt = System.currentTimeMillis())
        categoryDao.upsert(updated)
        journal.record(updated)
    }

    suspend fun archiveCategory(id: String, archived: Boolean = true) = db.withTransaction {
        val c = categoryDao.all().firstOrNull { it.id == id } ?: return@withTransaction
        val updated = c.copy(archived = archived, updatedAt = System.currentTimeMillis())
        categoryDao.upsert(updated)
        journal.record(updated)
    }

    suspend fun allTags(): List<TagEntity> = tagDao.all()

    fun observeTags(): Flow<List<TagEntity>> = tagDao.observeAll()

    suspend fun tags(): List<TagEntity> = tagDao.all()

    suspend fun findOrCreateTag(name: String): TagEntity = db.withTransaction {
        val dao = db.tagDao()
        dao.byName(name)?.let { return@withTransaction it }
        val now = System.currentTimeMillis()
        val tag = TagEntity(id = Ids.newId("tg-"), name = name, updatedAt = now)
        dao.upsert(tag)
        journal.record(tag)
        tag
    }
}
