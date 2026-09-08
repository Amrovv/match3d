package com.match3d.core;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Queued players ordered by wait time, longest waiting first.
 *
 * An array backed binary min heap keyed on queuedAt.
 * In addition to the array, a map from id to index in the array is maintained so
 * that player removal is O(log n) rather than O(n). 
 *
 * Ties on queuedAt break on id. Deterministic ordering.
 *
 * insert, poll and remove are O(log n), peek, contains and size are O(1). The
 * array doubles when full, so insert is O(1) amortised on the array itself.
 */
public final class FairnessHeap {

    private static final int INITIAL_CAPACITY = 16;

    private Player[] heap = new Player[INITIAL_CAPACITY];
    private final Map<UUID, Integer> positions = new HashMap<>();
    private int size = 0;


    /** 
     * Get the Parent index for the given index.
    */
    private int parent(int index) {
        return (index - 1) / 2;
    }

    /**
     * Get the Left Child index for the given index
    */
    private int left(int index) {
        return 2 * index + 1;
    }
    
    /**
     * Get the Right Child index for the given index
    */
    private int right(int index) {
        return 2 * index + 2;
    }

    /**
     * Adds a player
     *
     * Returns false if that id is already queued.
     */
    public boolean insert(Player player) {
        if (positions.containsKey(player.id())) return false;
        if (size == heap.length) grow();
        heap[size] = player;
        positions.put(player.id(), size);
        siftUp(size);
        size++;
        return true;
    }

    /**
     * The longest waiting player, without removing them, or null if empty.
     */
    public Player peek() {
        return heap[0];
    }

    /**
     * Removes and returns the longest waiting player, or null if empty.
     */
    public Player poll() {
        Player player = heap[0];
        if (player == null) return null;
        if (!remove(player.id())) return null;
        return player;
    }

    /**
     * Removes a player by id, wherever they sit in the heap.
     *
     * Returns false if that id is not queued.
     */
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

    /**
     * Moves the player at index up until their parent waits at least as long.
     */
    private void siftUp(int index) {
        while (index != 0 && (Player.BY_WAIT_TIME.compare(heap[index], heap[parent(index)]) < 0)){
            swap(index, parent(index));
            index = parent(index);
        }
    }

    /**
     * Moves the player at index down until both children wait at least as long.
     */
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
     * Swaps two entries, keeping the position map in step.
     *
     * Every move of a player inside the array must go through here, or the map
     * and the array drift apart and remove starts corrupting the heap.
     */
    private void swap(int a, int b) {
        Player playerA = heap[a];
        Player playerB = heap[b];

        heap[a] = playerB;
        heap[b] = playerA;

        positions.put(playerA.id(), b);
        positions.put(playerB.id(), a);

    }

    /**
     * Doubles the backing array when it is full.
     */
    private void grow() {
        Player[] newHeap = new Player[heap.length * 2];
        System.arraycopy(heap, 0, newHeap, 0, heap.length);
        heap = newHeap;
    }
}
