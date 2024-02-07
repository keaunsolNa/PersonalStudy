package com.greedy.section01.object.run;

import com.greedy.section01.object.book.dto.BookDTO;

public class Application1 {
	public static void main(String[] args) {

		/*
		 * 모든 클래스는 Object 클래스의 후손이다.
		 * 따라서 Object 클래스가 가진 메소드를 자신의 것처럼 사용할 수 있다.
		 * 또한 부모 클래스가 가지는 메소드를 오버라이딩 해서 사용하는 것도 가능하다.
		 * 
		 * Object 클래스의 메소드 중 관례상 많이 오버라이딩 해서 사용하는 메소드들이 있다.
		 * toString(), equals(), hashCode()
		 */
	BookDTO book1 = new BookDTO(1, "홍길동전", "허균", 50000);
	BookDTO book2 = new BookDTO(2, "목민심서", "정약용", 30000);
	BookDTO book3 = new BookDTO(3, "자바가 제일 쉬웠어요", "왕따", 10000);
	BookDTO book4 = new BookDTO(3, "자바가 제일 쉬웠어요", "왕따", 10000);

	/* Object로부터 물려받은 toString()은 풀 클래스명과 @ 그리고 16진수 해쉬코드를 반환한다. */
	System.out.println("book1.toString(): " + book1.toString());
	System.out.println("book2.toString(): " + book2.toString());
	System.out.println("book3.toString(): " + book3.toString());
	System.out.println("book4.toString(): " + book4.toString());

	/* print, println에서 인스턴스의 toString() 메소드를 실행할 때는 생략해도 동일하게 실행 가능하다. */
	System.out.println("book1.toString(): ");
	System.out.println("book2.toString(): ");
	System.out.println("book3.toString(): ");
	System.out.println("book4.toString(): ");
	}
}