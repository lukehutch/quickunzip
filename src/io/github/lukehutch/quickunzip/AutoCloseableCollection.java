/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.lukehutch.quickunzip;

import java.util.Collection;
import java.util.Iterator;

/** A list of AutoCloseable items that can be used in a try-with-resources block. */
class AutoCloseableCollection<T extends AutoCloseable> implements AutoCloseable, Collection<T> {
    private final Collection<T> coll;

    public AutoCloseableCollection(final Collection<T> coll) {
        this.coll = coll;
    }

    /** Call close() on all collection items. */
    @Override
    public void close() {
        for (final T item : coll) {
            try {
                item.close();
            } catch (final Exception e) {
            }
        }
    }

    @Override
    public int size() {
        return coll.size();
    }

    @Override
    public boolean isEmpty() {
        return coll.isEmpty();
    }

    @Override
    public boolean contains(final Object o) {
        return coll.contains(o);
    }

    @Override
    public Iterator<T> iterator() {
        return coll.iterator();
    }

    @Override
    public Object[] toArray() {
        return coll.toArray();
    }

    @Override
    public <U> U[] toArray(final U[] a) {
        return coll.toArray(a);
    }

    @Override
    public boolean add(final T e) {
        return coll.add(e);
    }

    @Override
    public boolean remove(final Object o) {
        return coll.remove(o);
    }

    @Override
    public boolean containsAll(final Collection<?> c) {
        return coll.containsAll(c);
    }

    @Override
    public boolean addAll(final Collection<? extends T> c) {
        return coll.addAll(c);
    }

    @Override
    public boolean removeAll(final Collection<?> c) {
        return coll.removeAll(c);
    }

    @Override
    public boolean retainAll(final Collection<?> c) {
        return coll.retainAll(c);
    }

    @Override
    public void clear() {
        coll.clear();
    }
}
