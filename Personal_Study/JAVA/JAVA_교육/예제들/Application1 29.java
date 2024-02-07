package com.greedy.section01.list.run;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Vector;

public class Application1 {
	public static void main(String[] args) {

		/* 컬렉션 프레임워크(Collection Framework) */
		/*
		 * 자바에서 컬렉션 프레임워크는 여러 개의 다양한 데이터들을 쉽고 효과적으로 처리할 수 있도록
		 * 표준화 된 방법을 제공하는 클래스들의 집합니다.
		 * (데이터를 효율적으로 저장하는 자료구조 + 데이터를 처리하는 알고리즘을 미리 구현해 놓은 클래스들)
		 * 
		 * Collection Framework는 크게 3가지 인터페이스 중 한가지를 구현해 놓았다.
		 * 1. List 인터페이스
		 * 2. Set 인터페이스
		 * 3. Map 인터페이스
		 * 
		 * List 인터페이스와 Set 인터페이스의 공통 부분은 Collection 인터페이스에서 정의하고 있다.
		 * 하지만 Map은 구조상의 차이로 Collection 인터페이스에서 정의하고 있지 않다.
		 */
		
		/*
		 * 각 인터페이스 별 특징
		 * 1. List 인터페이스
		 * 
		 * 2. Set 인터페이스
		 * 
		 * 3. Map 인터페이스
		 * 
		 */
		
		/* 
		 * ArrayList
		 * 가장 많이 사용되는 컬렉션 클래스이다.
		 * JDK 1.2부터 제공된다.
		 * 내부적으로 배열을 이용하여 요소를 관리하며, 인덱스를 이용해 배열 요소에 빠르게 접근할 수 있다.
		 * 
		 * ArrayList는 배열의 단점을 보완하기 위해 만들어 졌다.
		 * 배열은 크기를 변경할 수 없고, 요소의 추가, 삭제, 정렬 등이 
		 * 복잡(알고리즘 구현 필요)하다는 단점을 가지고 있다.
		 * ArrayList는 이러한 배열의 단점을 보완하고자 크기변경(새로운 더 큰 배열 만들어 옮기기);
		 * 요소의 추가, 삭제, 정렬, 수정 기능들을 미리 메소드로 구현해서 제공하고, 자동적으로 수행해 준다.
		 * (하지만 배열보다 속도가 빨라진다는 것은 아니다.)
		 */
		
		/* ArrayList는 인스턴스를 생성하게 되면 내부적으로 10칸짜리 배열을 생성해서 관리한다. */
//		ArrayList aList = new ArrayList();
		Vector aList = new Vector();			// Vector는 ArrayList와 달리 동기화 처리가 가능하다. (느리다 */
												// ArrayList와 다른 기능은 동일하다.
		
		/* 다향성을 적응하여 상위 레퍼런스로 ArrayList 객체를 만들수도 있다. (관례상 많이 사용하는 방식 */
		List list = new ArrayList();
		
		Collection collection = new ArrayList();	// 다향성을 적용해 이렇게도 가능은 하다.
		
		/*
		 * ArrayList는 저장 순서가 유지되며 index(순번)이 적용된다.
		 * aList는 Object 클래스의 하위 타입 인스턴스를 모두 저장할 수 있다. (단, 제네릭을 걸지 않았다면...)
		 */
		aList.add("apple");
		aList.add(123);					// autoBoxing 처리함(기본 자료형 값(int) -> 인스턴스(Integer))
		aList.add(45.67);
		aList.add(new java.util.Date());

		System.out.println(aList);
		
		/* 
		 * 내부에서 관리중인 배열의 크기가 아닌 요소의 개수를 반환한다.
		 * 내부적으로 관리하는 배열의 사이즈는 외부에서 알 필요가 없기 때문에 기능을 제공하지 않는다.
		 */
		System.out.println("aList의 size: " + aList.size());
		
		/* 내부 배열에 인덱스가 지정되어 있기 때문에 for문으로도 접근 후 확인하는 것이 가능하다. */
		for(int i = 0; i< aList.size(); i++) {
			System.out.println(i + "번째 인덱스: " + aList.get(i));
		}
		
//		for(Object obj : aList) {
//			System.out.println(obj);
//		}
		
		/* ArrayList는 데이터의 중복 저장을 허용한다.ㅏ */
		aList.add("apple");
		System.out.println("aList: " + aList);
		
		aList.remove(2);
		System.out.println("aList: " + aList);
		System.out.println("aList의 size: " + aList.size());
		
		aList.add(1, "banana");
		System.out.println("aList: " + aList);
		System.out.println("aList의 size: " + aList.size());
		
		aList.set(1, new Boolean(true));
		System.out.println("aList: " + aList);
		System.out.println("aList의 size: " + aList.size());
		
		/*
		 * 모든 컬렉션 프레임워크 클래스는 제네릭 클래스로 작성되어 있다.
		 * 자료형의 안전성을 보장하기 위해서 되려 제네릭을 적용한다. (타입 제한을 건다.)
		 */
		List<String> stringList = new ArrayList<>();
//		stringList.add(123);
		stringList.add("apple");
		stringList.add("banana");
		stringList.add("orange");
		stringList.add("mango");
		stringList.add("grape");
		
		System.out.println("stringList : " + stringList);
		
		/* ArrayList 안에 있는 String에 정의된 대로 문자열을 오름차순으로 정렬 */
		Collections.sort(stringList);
		System.out.println("stringList: " + stringList);
		
		/* 내림차순 */
		stringList = new LinkedList<>(stringList);
		
		/*
		 * iterator 반복자 인터페이스를 활용해서 역순으로 정렬
		 * 
		 *  Iterator란?
		 *  collection 인터페이스의 iterator() 메소드를 사용해서 인스턴스를 생성할 수 있다.
		 *  컬렉션에서 값을 읽어오는 방식을 통일된 방식으로 제공하기 위해 사용된다.
		 *  반복자라고 불리며, 반복문을 이용해서 목록을 하나씩 꺼내는 방식으로 사용하기 위함이다.
		 *  인덱스로 관리되는 컬렉션이 아닌 경우에는 반복문을 사용해서 요소에 하나씩 접근할 수 없기 때문에
		 *  인덱스를 사용하지 않고도 반복문을 사용하기 위한 목록을 만들어 주는 역할이라고 보면 된다.
		 *  
		 *  hasNext(): 다음 요소를 가지고 있는 경우 true, 더 이상 요소가 없는 경우 false를 반환
		 *  next(): 다음 요소를 반환하고 다음 요소 전으로 커서를 옮긴다.
		 */
		
		/* ascending: 오름차순, descending: 내림차순 */
		Iterator<String> dIter = ((LinkedList<String>)stringList).descendingIterator();
		while(dIter.hasNext()) {
			System.out.print(dIter.next() + " ");
		}

		/* 내림차순이 끝나고 다시 ArrayList에 담고 싶다면... */
		List<String> descendingArrayList = new ArrayList();
		while(dIter.hasNext()) {
			descendingArrayList.add(dIter.next());
		}
		
		System.out.println("descendingArrayList: " + descendingArrayList);
		
	}

}
