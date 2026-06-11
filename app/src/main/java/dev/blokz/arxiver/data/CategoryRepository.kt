package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.database.dao.CategoryDao
import dev.blokz.arxiver.core.database.dao.FollowDao
import dev.blokz.arxiver.core.database.entity.FollowEntity
import dev.blokz.arxiver.core.model.ArxivCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class CategoryWithFollowState(
    val category: ArxivCategory,
    val followed: Boolean,
)

@Singleton
class CategoryRepository
    @Inject
    constructor(
        private val categoryDao: CategoryDao,
        private val followDao: FollowDao,
    ) {
        /** Taxonomy grouped by top-level group, with live follow state. */
        fun observeGroupedCategories(): Flow<Map<String, List<CategoryWithFollowState>>> =
            combine(
                categoryDao.observeAll().map { list ->
                    list.map { ArxivCategory(code = it.code, name = it.name, group = it.groupName) }
                },
                followDao.observeFollowedCategoryCodes(),
            ) { categories, followedCodes ->
                val followed = followedCodes.toSet()
                categories
                    .map { CategoryWithFollowState(it, it.code in followed) }
                    .groupBy { it.category.group }
            }

        suspend fun setFollowed(
            category: ArxivCategory,
            followed: Boolean,
        ) {
            if (followed) {
                followDao.insert(
                    FollowEntity(
                        type = FollowEntity.TYPE_CATEGORY,
                        value = category.code,
                        label = category.name,
                        createdAt = Instant.now().toEpochMilli(),
                    ),
                )
            } else {
                followDao.delete(FollowEntity.TYPE_CATEGORY, category.code)
            }
        }
    }
