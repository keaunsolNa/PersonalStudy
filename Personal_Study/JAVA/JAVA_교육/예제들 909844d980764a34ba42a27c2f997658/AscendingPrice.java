package com.greedy.section01.list.comparator;

import java.util.Comparator;

import com.greedy.section01.list.run.BookDTO;

/* Comparator 인터페이스에는 제네릭을 한 번 걸어주고 다운캐스팅을 안하고 편하게 써보자. */
public class AscendingPrice implements Comparator<BookDTO>{

	/* 가격에 대한 오름차순 기준 생성 */
	@Override
	public int compare(BookDTO o1, BookDTO o2) {
		return o1.getPrice() - o2.getPrice();
	}

}
