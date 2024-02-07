package com.greedy.section01.list.comparator;

import java.util.Comparator;

import com.greedy.section01.list.run.BookDTO;

public class DescendingAuthor implements Comparator<BookDTO>{

	@Override
	public int compare(BookDTO o1, BookDTO o2) {
		return -o1.getAuthor().compareTo(o2.getAuthor());
	}

}
