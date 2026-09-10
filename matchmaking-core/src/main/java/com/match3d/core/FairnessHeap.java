package com.match3d.core;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Queued players ordered by wait time, longest waiting first. An array backed
 * binary min heap on queuedAt, with a map from id to array index so removal
 * from the middle is O(log n) rather than O(n).
 *
 * Ties break on id, so ordering is total and deterministic.
 *
 * insert, poll and remove are O(log n); peek, contains and size are O(1).
 *
 * Not synchronised. The caller supplies mutual exclusion.
 */
public final class FairnessHeap {

    private static final int INITIAL_CAPACITY = 16;

    private Player[] heap = new Player[INITIAL_CAPACITY];
    private final Map<UUID, Integer> positions = new HashMap<>();
    private int size = 0;


        private int parent(int index) {
        return (index - 1) / 2;
    }

        private int left(int index) {
        return 2 * index + 1;
    }
    
        private int right(int index) {
        return 2 * index + 2;
    }

    /** Adds a player. False if that id is already queued. */
    public boolean insert(Player player) {
        if (positions.containsKey(player.id())) return false;
        if (size == heap.length) grow();
        heap[size] = player;
        positions.put(player.id(), size);
        siftUp(size);
        size++;
        return true;
    }

    /** The longest waiting player, or null if empty. */
    public Player peek() {
        return heap[0];
    }

    /** Removes and returns the longest waiting player, or null if empty. */
    public Player poll() {
        Player player = heap[0];
        if (player == null) return null;
        if (!remove(player.id())) return null;
        return player;
    }

    /** Removes by id, wherever they sit. False if that id is not queued. */
    public boolean remove(UUID id) {
        Integer index = positions.remove(id);
        if (index == null) return false;

        size--;
        Player moved = heap[size];
        heap[size] = null;

        if (index < size) {
            heap[index] = moved;
            positions.put(moved.id(), index);
            siftDown(index);
            siftUp(index);
        }
        return true;
    }

    public boolean contains(UUID id) {
        return positions.containsKey(id);
    }

    public int size() {
        return size;
    }

    /** Up until the parent waits at least as long. */
    private void siftUp(int index) {
        while (index != 0 && (Player.BY_WAIT_TIME.compare(heap[index], heap[parent(index)]) < 0)){
            swap(index, parent(index));
            index = parent(index);
        }
    }

    /** Down until both children wait at least as long. */
    private void siftDown(int index) {
        int l = left(index);
        int r = right(index);

        int smallest = index;
        if (l < size && Player.BY_WAIT_TIME.compare(heap[l], heap[smallest]) < 0) {
            smallest = l;
        }
        if (r < size && Player.BY_WAIT_TIME.compare(heap[r], heap[smallest]) < 0) {
            smallest = r;
        }

        if (smallest != index) {
            swap(index, smallest);
            siftDown(smallest);
        }
    }

    /**
     * Every move inside the array goes through here, or the map and the array
     * drift apart and remove starts corrupting the heap silently.
     */
    private void swap(int a, int b) {
        Player playerA = heap[a];
        Player playerB = heap[b];

        heap[a] = playerB;
        heap[b] = playerA;

        positions.put(playerA.id(), b);
        positions.put(playerB.id(), a);

    }

    /** Doubles the backing array when it is full. */
    private void grow() {
        Player[] newHeap = new Player[heap.length * 2];
        System.arraycopy(heap, 0, newHeap, 0, heap.length);
        heap = newHeap;
    }
}
