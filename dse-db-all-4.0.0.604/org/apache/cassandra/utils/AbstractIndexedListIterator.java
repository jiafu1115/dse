package org.apache.cassandra.utils;

import com.google.common.base.Preconditions;
import com.google.common.collect.UnmodifiableListIterator;
import java.util.NoSuchElementException;

public abstract class AbstractIndexedListIterator<E> extends UnmodifiableListIterator<E> {
   protected final int size;
   private int position;
   private boolean reversed;

   protected abstract E get(int var1);

   protected AbstractIndexedListIterator(int size) {
      this(size, 0);
   }

   protected AbstractIndexedListIterator(int size, int position) {
      Preconditions.checkPositionIndex(position, size);
      this.size = size;
      this.position = position;
   }

   public final boolean hasNext() {
      return this.position < this.size;
   }

   public final E next() {
      if(!this.hasNext()) {
         throw new NoSuchElementException();
      } else {
         return this.get(this.position++);
      }
   }

   public final int nextIndex() {
      return this.position;
   }

   public final boolean hasPrevious() {
      return this.position > 0;
   }

   public final E previous() {
      if(!this.hasPrevious()) {
         throw new NoSuchElementException();
      } else {
         return this.get(--this.position);
      }
   }

   public final int previousIndex() {
      return this.position - 1;
   }
}
