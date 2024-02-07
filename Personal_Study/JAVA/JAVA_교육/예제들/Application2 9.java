package com.greedy.section01.object.run;

import com.greedy.section01.object.book.dto.BookDTO;

public class Application2 {

	public static void main(String[] args) {
		
		/*
		 * equals() 메소드 오버라이딩
		 * equals 메소드는 매개변수로 전달받은 인스턴스와 == 연산하여 true or false를 반환한다.
		 * 즉, 동일한 인스턴스인지를 비교하는 기능을 한다.
		 * 
		 * 동일 객체와 동등객체
		 * 동일객체 : 주소가 동일한 완전히 같은 인스턴스를 동일객체라고 한다. (하나의 인스턴스)
		 * 동등객체 : 주소는 다르더라도(다른 인스턴스일지라도) 필드 값이 도 ㅇ일한 객체를 동등객체라고 한다.
		 * 			(동등 하다고 할 기준을 정할 수 있다.)
		 * 
		 * equals() 메소드는 기본적으로 동일객체를 판단하는 기능을 담당한다. (Object의 원래 메소드에서는)
		 * 
		 * 우리는 동등객체를 판단하는 용도로 equals()메소드를 오버라이딩하여, 각각의 필드가 동일한 값을
		 * 가지는지를 확인할 수 있다.
		 */
		BookDTO book1 = new BookDTO(1, "홍길동전", "허균", 50000);
		BookDTO book2 = new BookDTO(1, "홍길동전", "허균", 50000);
		
		System.out.println("두 인스턴스의 == 연산 비교: " +(book1==book2));
		System.out.println("두 인스턴스의 equals() 비교: " +(book1.equals(book2)));
		
		
	}

}
