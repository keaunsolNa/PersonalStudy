package com.greedy.section02.set.run;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public class Application1 {
	public static void main(String[] args) {

		/* Set 인터페이스를 구현한 Set 컬렉션 클래스의 특징 */
		/*
		 * 1. 요소의 저장 순서를 유지하지 않는다. (LinkedHashSet 제외)
		 * 2. 같은 요소의 중복 저장을 허용하지 않는다. (null값도 중복되지 않게 하나의 null만 저장한다.)
		 */
		
		/*
		 * HashSet 클래스
		 * Set 컬렉션 클래스에서 가장 많이 사용되는 클래스 중 하나이다.
		 * JDK 1.2부터 제공되고 있으며 해쉬 알고리즘을 사용하여 검색 속도가 빠르다는 장점을 가진다.
		 */
		
		/* HashSet 인스턴스 생성 */
		HashSet<String> hSet = new HashSet<>();
		
		/* 다향성 적용하여 상위 인터페이스 타입으로 사용 가능 */
		Set<String> hSet2 = new HashSet<>();
		Collection<String> hSet3 = new HashSet<>();
		
		hSet.add(new String("java"));
		hSet.add(new String("oracle"));
		hSet.add(new String("jbdc"));
		hSet.add(new String("html"));
		hSet.add(new String("css"));
		
		/* 저장 된 값 확인 */
		System.out.println("hSet: " + hSet );
		
		/* 중복 허용 안함(동등 인스턴스(hashCode와 equals의 결과가 같은 인스턴스)는 하나만 저장한다. */
		hSet.add(new String("java"));
		
		System.out.println("hSet: " + hSet );
		System.out.println("저장 된 인스턴스 수: " + hSet.size());
		
		/* contains(): 인자로 전달 된 인스턴스와 동등한 인스턴스를 가지고 있으면 true를 반환 */
		System.out.println("포함 확인: " + hSet.contains(new String("oracle")));
		
		/* 반복문을 이용한 연속처리 방법 */
		/* 1. toArray()를 활용하여 배열(Object배열)로 바꾸고 for문을 사용 가능하게 할 수 있다. */
		Object[] arr = hSet.toArray();					// hashSet -> Object[]로 변경
		for(int i = 0; i < arr.length; i++) {
				System.out.println(i + " : " + ((String)arr[i]));
		}
		
		/* 2. iterator()로 목록 만들어 연속 처리 */
		Iterator<String> iter = hSet.iterator();
		while(iter.hasNext()) {
			System.out.println(iter.next());
		}
		
		/* 3. 지우는 방법 */
		hSet.clear();
		System.out.println("empty?: " + hSet.isEmpty());
		
		
	}
}
