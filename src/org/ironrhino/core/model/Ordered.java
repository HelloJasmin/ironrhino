package org.ironrhino.core.model;

public interface Ordered<T extends Ordered<T>> extends Comparable<T> {

	public int getDisplayOrder();

	@Override
	public default int compareTo(T o) {
		if (o == null)
			return 1;
		if (this.getDisplayOrder() != o.getDisplayOrder())
			return this.getDisplayOrder() - o.getDisplayOrder();
		return this.toString().compareTo(o.toString());
	}

}