package com.taxguard.conflict;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * An augmented BST (Interval Tree) for efficient date-range overlap queries.
 *
 * STRUCTURE:
 *   Each node stores an item T that has a [start, end] interval,
 *   plus a 'maxEnd' — the maximum 'end' value in the entire subtree.
 *
 * KEY INVARIANT:
 *   node.maxEnd = max(node.end, node.left.maxEnd, node.right.maxEnd)
 *   This invariant is maintained on every insert and enables pruning.
 *
 * PRUNING RULE (the O(log N) magic):
 *   If node.maxEnd < queryStart, then NO interval in this subtree can
 *   overlap the query range [queryStart, queryEnd]. Skip the entire subtree.
 *
 * COMPLEXITY:
 *   Build (N inserts):  O(N log N)
 *   Single query:       O(log N + K)  where K = number of results
 *   Full conflict scan: O(N log N + K)
 *
 * WHY NOT SEGMENT TREE?
 *   Segment trees require knowing all intervals upfront for coordinate compression.
 *   Interval trees support dynamic inserts — tax rules are added over time without
 *   requiring a full rebuild.
 *
 * @param <T> Any type that implements the Interval interface
 */
public class IntervalTree<T extends IntervalTree.Interval> {

    /** Any item stored in the tree must expose its date interval. */
    public interface Interval {
        LocalDate getStart();
        LocalDate getEnd();   // null = open-ended (treat as LocalDate.MAX)
    }

    private Node root;

    // ── Node ─────────────────────────────────────────────────────────────────

    private class Node {
        final T item;
        LocalDate maxEnd;   // Maximum end date in this subtree — the augmentation
        Node left, right;

        Node(T item) {
            this.item   = item;
            this.maxEnd = endOf(item);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Treat null end date as "far future" for all comparisons. */
    private LocalDate endOf(T item) {
        return item.getEnd() != null ? item.getEnd() : LocalDate.MAX;
    }

    private LocalDate maxOf(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private LocalDate subtreeMax(Node node) {
        return node != null ? node.maxEnd : LocalDate.MIN;
    }

    // ── Insert ────────────────────────────────────────────────────────────────

    /**
     * Insert an item. BST ordering by start date.
     * Updates maxEnd on every ancestor node (maintains the invariant).
     *
     * Note: This is an unbalanced BST. For production use, add AVL or
     * Red-Black rotations. For interview purposes, unbalanced is fine
     * and easier to reason about.
     */
    public void insert(T item) {
        root = insert(root, item);
    }

    private Node insert(Node node, T item) {
        if (node == null) return new Node(item);

        if (item.getStart().isBefore(node.item.getStart())) {
            node.left  = insert(node.left,  item);
        } else {
            node.right = insert(node.right, item);
        }

        // Propagate max endpoint upward — maintains the invariant after insert
        node.maxEnd = maxOf(endOf(node.item),
                     maxOf(subtreeMax(node.left), subtreeMax(node.right)));
        return node;
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    /**
     * Find ALL stored intervals that overlap with [queryStart, queryEnd].
     *
     * Two intervals [a, b] and [c, d] OVERLAP iff:
     *   a <= d  AND  c <= b
     * (equivalently: they do NOT fail to overlap in either direction)
     *
     * @param queryStart inclusive start of query range
     * @param queryEnd   inclusive end of query range (use LocalDate.MAX for open-ended)
     */
    public List<T> queryOverlapping(LocalDate queryStart, LocalDate queryEnd) {
        List<T> results = new ArrayList<>();
        queryOverlapping(root, queryStart, queryEnd, results);
        return results;
    }

    private void queryOverlapping(Node node, LocalDate qs, LocalDate qe, List<T> out) {
        if (node == null) return;

        // PRUNE: if the max end in this entire subtree is before our query start,
        // no interval here can possibly overlap — cut this entire branch.
        if (node.maxEnd.isBefore(qs)) return;

        // Check this node's interval: overlaps if start <= qe AND end >= qs
        if (!node.item.getStart().isAfter(qe) && !endOf(node.item).isBefore(qs)) {
            out.add(node.item);
        }

        // Recurse — pruning handles early termination in both subtrees
        queryOverlapping(node.left,  qs, qe, out);
        queryOverlapping(node.right, qs, qe, out);
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /** Returns the number of items stored in the tree. */
    public int size() {
        return size(root);
    }

    private int size(Node node) {
        return node == null ? 0 : 1 + size(node.left) + size(node.right);
    }

    public boolean isEmpty() {
        return root == null;
    }
}
