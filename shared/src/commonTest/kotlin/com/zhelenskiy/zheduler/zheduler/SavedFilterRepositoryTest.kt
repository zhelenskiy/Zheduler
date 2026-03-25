@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class InMemorySavedFilterRepositoryTest : SavedFilterRepositoryTest(), InMemoryRepositoryTest
class DatabaseSavedFilterRepositoryTest : SavedFilterRepositoryTest(), DatabaseRepositoryTest

abstract class SavedFilterRepositoryTest : AbstractRepositoryTest {

    // ==================== Basic CRUD Tests ====================

    @Test
    fun `getAllSavedFilters returns empty list for new space`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val filters = repo.getAllSavedFilters(spaceId)
        assertTrue(filters.isEmpty())
    }

    @Test
    fun `saveSavedFilter creates new filter`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val filter = SavedFilter(
            id = "filter-1",
            name = "My Filter",
            spaceId = spaceId,
            criteria = TaskFilterCriteria(searchQuery = "test"),
            viewModeId = null
        )

        val saved = repo.saveSavedFilter(filter)

        assertEquals(filter.id, saved.id)
        assertEquals(filter.name, saved.name)
        assertEquals(filter.criteria.searchQuery, saved.criteria.searchQuery)
    }

    @Test
    fun `getAllSavedFilters returns saved filters`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val filter1 = SavedFilter(
            id = "filter-1",
            name = "Filter 1",
            spaceId = spaceId,
            criteria = TaskFilterCriteria(searchQuery = "test1")
        )
        val filter2 = SavedFilter(
            id = "filter-2",
            name = "Filter 2",
            spaceId = spaceId,
            criteria = TaskFilterCriteria(searchQuery = "test2")
        )

        repo.saveSavedFilter(filter1)
        repo.saveSavedFilter(filter2)

        val filters = repo.getAllSavedFilters(spaceId)
        assertEquals(2, filters.size)
        assertTrue(filters.any { it.name == "Filter 1" })
        assertTrue(filters.any { it.name == "Filter 2" })
    }

    @Test
    fun `getSavedFilterById returns correct filter`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val filter = SavedFilter(
            id = "filter-1",
            name = "My Filter",
            spaceId = spaceId,
            criteria = TaskFilterCriteria(searchQuery = "test")
        )

        repo.saveSavedFilter(filter)

        val retrieved = repo.getSavedFilterById(spaceId, "filter-1")
        assertNotNull(retrieved)
        assertEquals("My Filter", retrieved.name)
        assertEquals("test", retrieved.criteria.searchQuery)
    }

    @Test
    fun `getSavedFilterById returns null for non-existent filter`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val retrieved = repo.getSavedFilterById(spaceId, "non-existent")
        assertNull(retrieved)
    }

    @Test
    fun `saveSavedFilter updates existing filter`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val original = SavedFilter(
            id = "filter-1",
            name = "Original Name",
            spaceId = spaceId,
            criteria = TaskFilterCriteria(searchQuery = "original")
        )

        repo.saveSavedFilter(original)

        val updated = original.copy(
            name = "Updated Name",
            criteria = TaskFilterCriteria(searchQuery = "updated")
        )
        repo.saveSavedFilter(updated)

        val retrieved = repo.getSavedFilterById(spaceId, "filter-1")
        assertNotNull(retrieved)
        assertEquals("Updated Name", retrieved.name)
        assertEquals("updated", retrieved.criteria.searchQuery)
    }

    @Test
    fun `deleteSavedFilter removes filter`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val filter = SavedFilter(
            id = "filter-1",
            name = "To Delete",
            spaceId = spaceId,
            criteria = TaskFilterCriteria()
        )

        repo.saveSavedFilter(filter)
        assertTrue(repo.deleteSavedFilter(spaceId, "filter-1"))
        assertNull(repo.getSavedFilterById(spaceId, "filter-1"))
    }

    @Test
    fun `deleteSavedFilter returns false for non-existent filter`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        assertFalse(repo.deleteSavedFilter(spaceId, "non-existent"))
    }

    // ==================== View Mode Attachment Tests ====================

    @Test
    fun `saveSavedFilter with attached view mode saves viewModeId`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val viewMode = repo.getAllViewModes(spaceId).first()

        val filter = SavedFilter(
            id = "filter-1",
            name = "With View Mode",
            spaceId = spaceId,
            criteria = TaskFilterCriteria(),
            viewModeId = viewMode.id
        )

        repo.saveSavedFilter(filter)

        val retrieved = repo.getSavedFilterById(spaceId, "filter-1")
        assertNotNull(retrieved)
        assertEquals(viewMode.id, retrieved.viewModeId)
    }

    @Test
    fun `saveSavedFilter without view mode saves null viewModeId`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val filter = SavedFilter(
            id = "filter-1",
            name = "Without View Mode",
            spaceId = spaceId,
            criteria = TaskFilterCriteria(),
            viewModeId = null
        )

        repo.saveSavedFilter(filter)

        val retrieved = repo.getSavedFilterById(spaceId, "filter-1")
        assertNotNull(retrieved)
        assertNull(retrieved.viewModeId)
    }

    // ==================== Complex Criteria Tests ====================

    @Test
    fun `saveSavedFilter preserves complex criteria`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val complexCriteria = TaskFilterCriteria(
            searchQuery = "complex search",
            statusFilters = persistentSetOf(TaskStatus.Open, TaskStatus.InProgress),
            priorityFilter = PriorityFilter.High,
            dueDateFilter = DueDateFilter.ThisWeek,
            selectedTags = persistentSetOf("tag1", "tag2"),
            tagMatchMode = TagMatchMode.All,
            estimatedTimeFilter = EstimatedTimeFilter.Quick,
            recurrenceFilter = RecurrenceFilter.HasRecurrence,
            notificationsFilter = NotificationsFilter.HasNotifications,
            autoUpdateStatusFilter = AutoUpdateStatusFilter.Auto
        )

        val filter = SavedFilter(
            id = "filter-complex",
            name = "Complex Filter",
            spaceId = spaceId,
            criteria = complexCriteria
        )

        repo.saveSavedFilter(filter)

        val retrieved = repo.getSavedFilterById(spaceId, "filter-complex")
        assertNotNull(retrieved)
        assertEquals("complex search", retrieved.criteria.searchQuery)
        assertEquals(persistentSetOf(TaskStatus.Open, TaskStatus.InProgress), retrieved.criteria.statusFilters)
        assertEquals(PriorityFilter.High, retrieved.criteria.priorityFilter)
        assertEquals(DueDateFilter.ThisWeek, retrieved.criteria.dueDateFilter)
        assertEquals(persistentSetOf("tag1", "tag2"), retrieved.criteria.selectedTags)
        assertEquals(TagMatchMode.All, retrieved.criteria.tagMatchMode)
        assertEquals(EstimatedTimeFilter.Quick, retrieved.criteria.estimatedTimeFilter)
        assertEquals(RecurrenceFilter.HasRecurrence, retrieved.criteria.recurrenceFilter)
        assertEquals(NotificationsFilter.HasNotifications, retrieved.criteria.notificationsFilter)
        assertEquals(AutoUpdateStatusFilter.Auto, retrieved.criteria.autoUpdateStatusFilter)
    }

    // ==================== Space Isolation Tests ====================

    @Test
    fun `filters are isolated by space`() = runTest {
        val repo = createEmptyRepository()
        val space1 = repo.createSpace("Space 1", "SP")!!
        val space2 = repo.createSpace("Space 2", "SPT")!!

        val filter1 = SavedFilter(
            id = "filter-1",
            name = "Filter in Space 1",
            spaceId = space1.id,
            criteria = TaskFilterCriteria(searchQuery = "space1")
        )
        val filter2 = SavedFilter(
            id = "filter-1", // Same ID but different space
            name = "Filter in Space 2",
            spaceId = space2.id,
            criteria = TaskFilterCriteria(searchQuery = "space2")
        )

        repo.saveSavedFilter(filter1)
        repo.saveSavedFilter(filter2)

        val space1Filters = repo.getAllSavedFilters(space1.id)
        val space2Filters = repo.getAllSavedFilters(space2.id)

        assertEquals(1, space1Filters.size)
        assertEquals("Filter in Space 1", space1Filters.first().name)
        assertEquals(1, space2Filters.size)
        assertEquals("Filter in Space 2", space2Filters.first().name)
    }

    @Test
    fun `deleting space removes its saved filters`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val filter = SavedFilter(
            id = "filter-1",
            name = "To Delete With Space",
            spaceId = spaceId,
            criteria = TaskFilterCriteria()
        )

        repo.saveSavedFilter(filter)
        assertEquals(1, repo.getAllSavedFilters(spaceId).size)

        repo.deleteSpace(spaceId)

        // After space deletion, filters should be gone
        val filters = repo.getAllSavedFilters(spaceId)
        assertTrue(filters.isEmpty())
    }

    // ==================== getAllSavedFiltersWithViewModes Tests ====================

    @Test
    fun `getAllSavedFiltersWithViewModes returns empty list for new space`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val filtersWithViewModes = repo.getAllSavedFiltersWithViewModes(spaceId)
        assertTrue(filtersWithViewModes.isEmpty())
    }

    @Test
    fun `getAllSavedFiltersWithViewModes returns filter with null viewMode when no viewModeId`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val filter = SavedFilter(
            id = "filter-1",
            name = "No View Mode",
            spaceId = spaceId,
            criteria = TaskFilterCriteria(),
            viewModeId = null
        )

        repo.saveSavedFilter(filter)

        val filtersWithViewModes = repo.getAllSavedFiltersWithViewModes(spaceId)
        assertEquals(1, filtersWithViewModes.size)
        assertEquals("filter-1", filtersWithViewModes.first().filter.id)
        assertNull(filtersWithViewModes.first().attachedViewMode)
    }

    @Test
    fun `getAllSavedFiltersWithViewModes resolves attached built-in view mode`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val builtInViewMode = repo.getAllViewModes(spaceId).first()

        val filter = SavedFilter(
            id = "filter-1",
            name = "With Built-in View Mode",
            spaceId = spaceId,
            criteria = TaskFilterCriteria(),
            viewModeId = builtInViewMode.id
        )

        repo.saveSavedFilter(filter)

        val filtersWithViewModes = repo.getAllSavedFiltersWithViewModes(spaceId)
        assertEquals(1, filtersWithViewModes.size)
        assertNotNull(filtersWithViewModes.first().attachedViewMode)
        assertEquals(builtInViewMode.id, filtersWithViewModes.first().attachedViewMode?.id)
        assertEquals(builtInViewMode.name, filtersWithViewModes.first().attachedViewMode?.name)
    }

    @Test
    fun `getAllSavedFiltersWithViewModes resolves attached custom view mode`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val customViewMode = ViewMode(
            id = "custom-view-mode",
            name = "My Custom View",
            spaceId = spaceId,
            groupingLevels = persistentListOf(),
            defaultOrderingRules = persistentListOf()
        )
        repo.saveViewMode(customViewMode)

        val filter = SavedFilter(
            id = "filter-1",
            name = "With Custom View Mode",
            spaceId = spaceId,
            criteria = TaskFilterCriteria(),
            viewModeId = customViewMode.id
        )

        repo.saveSavedFilter(filter)

        val filtersWithViewModes = repo.getAllSavedFiltersWithViewModes(spaceId)
        assertEquals(1, filtersWithViewModes.size)
        assertNotNull(filtersWithViewModes.first().attachedViewMode)
        assertEquals("custom-view-mode", filtersWithViewModes.first().attachedViewMode?.id)
        assertEquals("My Custom View", filtersWithViewModes.first().attachedViewMode?.name)
    }

    @Test
    fun `getAllSavedFiltersWithViewModes returns null viewMode for non-existent viewModeId`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val filter = SavedFilter(
            id = "filter-1",
            name = "With Invalid View Mode",
            spaceId = spaceId,
            criteria = TaskFilterCriteria(),
            viewModeId = "non-existent-view-mode"
        )

        repo.saveSavedFilter(filter)

        val filtersWithViewModes = repo.getAllSavedFiltersWithViewModes(spaceId)
        assertEquals(1, filtersWithViewModes.size)
        assertEquals("non-existent-view-mode", filtersWithViewModes.first().filter.viewModeId)
        assertNull(filtersWithViewModes.first().attachedViewMode)
    }

    @Test
    fun `getAllSavedFiltersWithViewModes handles multiple filters with mixed view modes`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val builtInViewMode = repo.getAllViewModes(spaceId).first()
        val customViewMode = ViewMode(
            id = "custom-view-mode",
            name = "Custom View",
            spaceId = spaceId,
            groupingLevels = persistentListOf(),
            defaultOrderingRules = persistentListOf()
        )
        repo.saveViewMode(customViewMode)

        val filter1 = SavedFilter(
            id = "filter-1",
            name = "No View Mode",
            spaceId = spaceId,
            criteria = TaskFilterCriteria(),
            viewModeId = null
        )
        val filter2 = SavedFilter(
            id = "filter-2",
            name = "Built-in View Mode",
            spaceId = spaceId,
            criteria = TaskFilterCriteria(),
            viewModeId = builtInViewMode.id
        )
        val filter3 = SavedFilter(
            id = "filter-3",
            name = "Custom View Mode",
            spaceId = spaceId,
            criteria = TaskFilterCriteria(),
            viewModeId = customViewMode.id
        )
        val filter4 = SavedFilter(
            id = "filter-4",
            name = "Invalid View Mode",
            spaceId = spaceId,
            criteria = TaskFilterCriteria(),
            viewModeId = "invalid-id"
        )

        repo.saveSavedFilter(filter1)
        repo.saveSavedFilter(filter2)
        repo.saveSavedFilter(filter3)
        repo.saveSavedFilter(filter4)

        val filtersWithViewModes = repo.getAllSavedFiltersWithViewModes(spaceId)
        assertEquals(4, filtersWithViewModes.size)

        val byId = filtersWithViewModes.associateBy { it.filter.id }
        assertNull(byId["filter-1"]?.attachedViewMode)
        assertEquals(builtInViewMode.id, byId["filter-2"]?.attachedViewMode?.id)
        assertEquals(customViewMode.id, byId["filter-3"]?.attachedViewMode?.id)
        assertNull(byId["filter-4"]?.attachedViewMode)
    }

    @Test
    fun `getAllSavedFiltersWithViewModes returns null viewMode after deleting attached view mode`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val customViewMode = ViewMode(
            id = "to-delete-view-mode",
            name = "To Delete",
            spaceId = spaceId,
            groupingLevels = persistentListOf(),
            defaultOrderingRules = persistentListOf()
        )
        repo.saveViewMode(customViewMode)

        val filter = SavedFilter(
            id = "filter-1",
            name = "With View Mode To Delete",
            spaceId = spaceId,
            criteria = TaskFilterCriteria(),
            viewModeId = customViewMode.id
        )
        repo.saveSavedFilter(filter)

        // Verify view mode is resolved initially
        var filtersWithViewModes = repo.getAllSavedFiltersWithViewModes(spaceId)
        assertNotNull(filtersWithViewModes.first().attachedViewMode)

        // Delete the view mode
        repo.deleteViewMode(spaceId, customViewMode.id)

        // Now the view mode should not resolve
        filtersWithViewModes = repo.getAllSavedFiltersWithViewModes(spaceId)
        assertEquals(1, filtersWithViewModes.size)
        assertEquals("to-delete-view-mode", filtersWithViewModes.first().filter.viewModeId)
        assertNull(filtersWithViewModes.first().attachedViewMode)
    }

    @Test
    fun `getAllSavedFiltersWithViewModes is isolated by space`() = runTest {
        val repo = createEmptyRepository()
        val space1 = repo.createSpace("Space 1", "SPA")!!
        val space2 = repo.createSpace("Space 2", "SPB")!!

        val viewMode1 = repo.getAllViewModes(space1.id).first()
        val viewMode2 = repo.getAllViewModes(space2.id).first()

        val filter1 = SavedFilter(
            id = "filter-1",
            name = "Space 1 Filter",
            spaceId = space1.id,
            criteria = TaskFilterCriteria(),
            viewModeId = viewMode1.id
        )
        val filter2 = SavedFilter(
            id = "filter-1",
            name = "Space 2 Filter",
            spaceId = space2.id,
            criteria = TaskFilterCriteria(),
            viewModeId = viewMode2.id
        )

        repo.saveSavedFilter(filter1)
        repo.saveSavedFilter(filter2)

        val space1Filters = repo.getAllSavedFiltersWithViewModes(space1.id)
        val space2Filters = repo.getAllSavedFiltersWithViewModes(space2.id)

        assertEquals(1, space1Filters.size)
        assertEquals("Space 1 Filter", space1Filters.first().filter.name)
        assertNotNull(space1Filters.first().attachedViewMode)

        assertEquals(1, space2Filters.size)
        assertEquals("Space 2 Filter", space2Filters.first().filter.name)
        assertNotNull(space2Filters.first().attachedViewMode)
    }
}
