package com.orbit.wellness.domain.topology

class UnionFind(size: Int) {
    private val parent = IntArray(size) { it }
    private val rank = IntArray(size)

    fun find(index: Int): Int {
        var current = index
        while (parent[current] != current) {
            parent[current] = parent[parent[current]]
            current = parent[current]
        }
        return current
    }

    fun union(a: Int, b: Int): Int {
        var rootA = find(a)
        var rootB = find(b)
        if (rootA == rootB) return rootA
        if (rank[rootA] < rank[rootB]) {
            val tmp = rootA
            rootA = rootB
            rootB = tmp
        }
        parent[rootB] = rootA
        if (rank[rootA] == rank[rootB]) rank[rootA]++
        return rootA
    }
}
