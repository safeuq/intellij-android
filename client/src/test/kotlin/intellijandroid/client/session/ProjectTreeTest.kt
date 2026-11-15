package intellijandroid.client.session

import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectTreeTest {
    private fun node(id: Int, parentId: Int?, name: String, sortKey: Int = 0, hasChildren: Boolean = false) =
        TreeNode(id, parentId, listOf(TextFragment(name, 0, null)), emptyList(), hasChildren, hasChildren, null, sortKey)

    @Test
    fun visibleRowsFollowSortKeysAndOnlyShowChildrenOfExpandedNodes() {
        // Like the backend sends them for the smoke project: roots with sort keys -1, 0, 1; children of the project.
        val nodes = listOf(
            node(2, null, "External Libraries", sortKey = 0, hasChildren = true),
            node(1, null, "smoke", sortKey = -1, hasChildren = true),
            node(3, null, "Scratches and Consoles", sortKey = 1),
            node(5, 1, "src", hasChildren = true),
            node(4, 1, ".idea", hasChildren = true),
            node(6, 5, "Main.java"),
        ).associateBy { it.id }

        val collapsed = ProjectTree(nodes, expanded = emptySet())
        assertEquals(listOf("smoke" to 1, "External Libraries" to 1, "Scratches and Consoles" to 1), collapsed.visibleRows().map { it.first.fragments.single().text to it.second })

        val expanded = ProjectTree(nodes, expanded = setOf(1, 5))
        assertEquals(
            listOf("smoke" to 1, ".idea" to 2, "src" to 2, "Main.java" to 3, "External Libraries" to 1, "Scratches and Consoles" to 1),
            expanded.visibleRows().map { it.first.fragments.single().text to it.second },
        )
    }
}
