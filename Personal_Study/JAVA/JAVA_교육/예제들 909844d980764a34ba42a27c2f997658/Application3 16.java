package com.greedy.section01.list.run;

import java.util.LinkedList;
import java.util.List;

public class Application3 {
	public static void main(String[] args) {

		/* LinkedList */
		/*
		 * ArrayList가 배열을 이용해서 발생할 수 있는 성능적인 단점을 보완하고자 고안되었다.
		 * 내부는 이중 연결리스트로 구현되어 있다.
		 * 
		 * 이중 연결 리스트
		 * : 단일 연결 리스트는 다음 요소만 링크하는 반면 이중 연결 리스트는 이전 요소도 링크하여
		 * 	 이전 요소로 접근하기 쉽게 고안된 자료구조이다.
		 * 	 요소의 저장과 삭제 시 다음 요소나 이전 요소를 가리키는 참조 링크만 변경하면 되기 때문에
		 * 	 요소의 저장과 삭제가 번번히 일어나는 경우 ArrayList보다 성능 면에서 우수하다.
		 * 
		 * 이와 같이 같은 List 계열이라도 요소를 저장하는 방법에 차이가 있으ㅡ므로
		 * 각 컬렉션 프레임워크 클래스들의 특징을 파악하고 그에 따라 적합한 자료구조를 구현한 클래스를
		 * 선택하는 것이 좋다.
		 */
		
		/* LinkedList 인스턴스 생성 */
		List<String> linkedList = new LinkedList<>();
		
		/* 요소를 추가할 때는 add를 사용한다. */
		linkedList.add("apple");
		linkedList.add("banana");
		linkedList.add("orange");
		linkedList.add("mango");
		linkedList.add("grape");

		System.out.println("linkedList: " +linkedList);
		
		/* 저장된 요소의 개수는 size() 메소드를 이용한다. */
		System.out.println("size: " + linkedList.size());
		
		/* for문과 size(), 그리고 get()을 활용해서 반복문으로 요소를 확인할 수 있다. */
		for(int i = 0; i < linkedList.size(); i++) {
			System.out.println(i + " : " + linkedList.get(i));
		}
		
		/* 요소를 제거할 때는 remove() 메소드를 이용하여 인덱스를 활용한다. */
		linkedList.remove(1);				// 1번 인덱스의 banana 삭제
		
		/* 향상된 for문도 사용 가능하다. */
		for(String s : linkedList) {
			System.out.println(s);
		}
		
		/* set()을 메소드를 이용해서 요소를 수정할 수도 있다. */
		linkedList.set(0, "pineapple");
		
		System.out.println(linkedList);
		
		/* isEmpty() 메소드를 이용해서 list가 비어있는지를 확인할 수 있다. */
		System.out.println(linkedList.isEmpty());
		
		/* 리스트 내 요소를 모두 제거하는 clear() 메소드를 이용할 수도 있다. */
		linkedList.clear();
		
		System.out.println(linkedList.isEmpty());
		System.out.println(linkedList.size());
		
	}
}
