package com.greedy.section01.object.run;

import java.util.HashMap;
import java.util.Map;

import com.greedy.section01.object.book.dto.BookDTO;

public class Application3 {
	public static void main(String[] args) {
		
		/* 동등객체 생성 후 hashCode 출력 */
		BookDTO book1 = new BookDTO(1, "홍길동전", "허균", 50000);
		BookDTO book2 = new BookDTO(1, "홍길동전", "허균", 50000);

		/*
		 * 동일한 필드 값을 가지고 있다면 두 인스턴스는 서로 다른 인스턴스이다.
		 * 따라서 hashCode값은 다르게 나오게 된다.
		 * 하지만 우리는 동등객체이면 hashCode값이 같게 나오도록 hashCode()를 오버라이딩 하자.
		 */
		System.out.println("book1의 hashCode: " + book1.hashCode());
		System.out.println("book2의 hashCode: " + book2.hashCode());
		
		Map<BookDTO, String> map = new HashMap<>();
		map.put(new BookDTO(1, "홍길동전", "허균", 50000), "sell");
		
		String str = map.get(new BookDTO(1, "홍길동전", "허균", 50000));
		System.out.println("value는 : " +str);
		/*
		 * Map 은 저장공간. 단, Map에 저장할 때 순서 없이 저장한다. 이후 Map 안에 있는 값들에
		 * 꼬리표(Key)를 붙여 저장하고, 꼬리표와 값(value)을 묶어 Generic으로 관리한다. 이후
		 * 꼬리표를 put으로 넣고, 값을 get으로 가져온다. 이후 hashCode와 equals를 이용, 밖에
		 * 있는 꼬리표와 비교해 가져온다.
		 */
	}
}
