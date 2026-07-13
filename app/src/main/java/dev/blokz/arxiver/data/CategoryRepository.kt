package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.database.dao.CategoryDao
import dev.blokz.arxiver.core.database.dao.FollowDao
import dev.blokz.arxiver.core.database.dao.InboxDao
import dev.blokz.arxiver.core.database.entity.FollowEntity
import dev.blokz.arxiver.core.model.ArxivCategory
import dev.blokz.arxiver.core.model.Source
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
        private val inboxDao: InboxDao,
    ) {
        /**
         * Taxonomy grouped by top-level group, with live follow state. The follow Boolean is **arXiv-scoped**
         * (`observeFollowedCategoryCodes` filters `origin='arxiv'`), so following the same category code on a
         * non-arXiv source (P-Feeds PF.3) never lights up the arXiv grid row.
         */
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

        /**
         * Count of enabled follows across ALL origins (P-Feeds PF.3) — the origin-agnostic "has any follows"
         * signal for Today. NOT the arXiv-scoped grid observation, so a non-arXiv-only follower still counts.
         */
        fun observeEnabledFollowCount(): Flow<Int> = followDao.observeEnabledFollowCount()

        /** All follow rows (any origin/type) — the source-follow picker derives per-(origin, value) toggle state. */
        fun observeFollows(): Flow<List<FollowEntity>> = followDao.observeAll()

        /** The arXiv taxonomy-grid follow toggle (origin = arXiv). */
        suspend fun setFollowed(
            category: ArxivCategory,
            followed: Boolean,
        ) = setCategoryFollowed(
            value = category.code,
            label = category.name,
            source = Source.ARXIV,
            followed = followed,
        )

        /**
         * Origin-aware category follow toggle (P-Feeds PF.3): the follow keys on (type, value, `origin`), so the
         * same category can be followed on multiple sources independently. On unfollow, the follow's inbox rows
         * are cleaned in the same operation so an unfollowed feed's items don't dangle on a dead `follow_id`.
         * [value] is the backend category token (a bio/med server string, an OpenAlex `fields/N`, or `""` for a
         * whole-source follow).
         */
        suspend fun setCategoryFollowed(
            value: String,
            label: String,
            source: Source,
            followed: Boolean,
        ) {
            if (followed) {
                followDao.insert(
                    FollowEntity(
                        type = FollowEntity.TYPE_CATEGORY,
                        value = value,
                        label = label,
                        origin = source.wire,
                        createdAt = Instant.now().toEpochMilli(),
                    ),
                )
            } else {
                followDao.find(FollowEntity.TYPE_CATEGORY, value, source.wire)?.let {
                    inboxDao.deleteByFollowId(it.id)
                }
                followDao.delete(FollowEntity.TYPE_CATEGORY, value, source.wire)
            }
        }

        /**
         * Follow/unfollow an author by name (P-Discover2 PD.1). Author follows are ALWAYS `origin=arxiv`: sync
         * dispatches on origin (`FollowSyncWorker` — a non-arXiv author would mis-route to the whole-source backend
         * browse), and arXiv's `au:"name"` is the only author feed. It's a free-text match (arXiv has no canonical
         * author id), so namesakes can collide — the follow UI says so. Unfollow cleans the inbox rows this follow
         * surfaced, exactly like [setCategoryFollowed].
         */
        suspend fun setAuthorFollowed(
            name: String,
            followed: Boolean,
        ) {
            val origin = Source.ARXIV.wire
            if (followed) {
                followDao.insert(
                    FollowEntity(
                        type = FollowEntity.TYPE_AUTHOR,
                        value = name,
                        label = name,
                        origin = origin,
                        createdAt = Instant.now().toEpochMilli(),
                    ),
                )
            } else {
                followDao.find(FollowEntity.TYPE_AUTHOR, name, origin)?.let {
                    inboxDao.deleteByFollowId(it.id)
                }
                followDao.delete(FollowEntity.TYPE_AUTHOR, name, origin)
            }
        }

        /**
         * Remove any follow (P-FeedPolish PFP.2 manage screen) — type- and origin-aware, unlike
         * [setCategoryFollowed], which only ever deletes `category` follows. This is the only path that can drop an
         * author/query follow. Cleans the follow's inbox rows in the same operation (the unfollow contract), keyed
         * by the entity's own [FollowEntity.id] so no extra `find` round-trip is needed.
         */
        suspend fun removeFollow(follow: FollowEntity) {
            inboxDao.deleteByFollowId(follow.id)
            followDao.delete(follow.type, follow.value, follow.origin)
        }
    }
