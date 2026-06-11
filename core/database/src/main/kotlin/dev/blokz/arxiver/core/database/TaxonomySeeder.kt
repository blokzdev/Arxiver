package dev.blokz.arxiver.core.database

import dev.blokz.arxiver.core.database.dao.CategoryDao
import dev.blokz.arxiver.core.database.entity.CategoryEntity
import dev.blokz.arxiver.core.model.ArxivTaxonomy

/**
 * Idempotently seeds the bundled arXiv taxonomy (SPEC-DATA §2) on app start.
 * Upsert keeps user databases current when the bundled list gains categories.
 */
class TaxonomySeeder(private val categoryDao: CategoryDao) {
    suspend fun seed() {
        categoryDao.upsertAll(
            ArxivTaxonomy.categories.map {
                CategoryEntity(code = it.code, name = it.name, groupName = it.group)
            },
        )
    }
}
