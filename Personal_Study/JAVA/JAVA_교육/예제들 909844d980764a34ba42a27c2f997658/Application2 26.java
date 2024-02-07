package com.greedy.section01.list.run;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import com.greedy.section01.list.comparator.AscendingPrice;
import com.greedy.section01.list.comparator.DescendingAuthor;

public class Application2 {
	public static void main(String[] args) {

		/* ArrayList의 용법과 정렬에 대해 조금 더 살펴보자. */
		List<BookDTO> bookList = new ArrayList();

		bookList.add(new BookDTO(1, "홍길동전", "허균", 50000));
		bookList.add(new BookDTO(2, "목민심서", "정약용", 30000));
		bookList.add(new BookDTO(3, "동의보감", "허준", 60000));
		bookList.add(new BookDTO(4, "삼국사기", "김부식", 46000));
		bookList.add(new BookDTO(5, "삼국유사", "일면", 35000));
	
		/* Collections 클래스 활용 */
		/* 1. Comparable 인터페이스 활용 시(DTO(컬렉션이 저장한 객체 타입)에) */
//		Collections.sort(bookList);
		
		/* 2. Comparator 인터페이스 활용 시 (정렬 기준마다 클래스에) */
//		Collections.sort(bookList, new AscendingPrice());
//		Collections.sort(bookList, new DescendingAuthor());

		/* List계열 클래스 활용 */
		/* 1. Comparable 인터페이스 활용 시 */
//		bookList.sort(null);
		
		/* 2. Comparator 인터페이스 활용 시 */
		bookList.sort(new AscendingPrice());
		
		/* ArrayList 출력하는 법 */
		/* 1. ArrayList에 정의 된 toString()을 활용하는 방법 */
		System.out.println("bookList: " + bookList);

		/* 2. for문을 통한 출력 */
//		for(int i = 0; i <bookList.size(); i++) {
//			System.out.println(bookList.get(i));
//		}
		
		/* 3. for-each문을 통한 출력(향상된 for문) */
//		for(BookDTO b : bookList) {
//			System.out.println(b);
//		}
		
		/* 4. 반복자(Iterator) */
		Iterator<BookDTO> iter = bookList.iterator();
		while(iter.hasNext()) {
			System.out.println(iter.next());
		}
		
		
		
	}

}
