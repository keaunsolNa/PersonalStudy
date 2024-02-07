package com.greedy.section02.set.run;

import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

public class Application3 {
	public static void main(String[] args) {

		/* TreeSet 클래스 */
		TreeSet<String> tSet = new TreeSet<>();
		Set<String> tSet2 = new TreeSet<>();
		
		tSet2.add("java");
		tSet2.add("java");
		tSet2.add("oracle");
		tSet2.add("jdbc");
		tSet2.add("html");
		tSet2.add("html");
		tSet2.add("css");
		
		/* 오름차순 */
		System.out.println(tSet2);			// String의 Comparable의 CompareTo API를 
											// 오버라이드하였기에 오름차순으로 자동정렬된다.
		/* 내림차순 */
		System.out.println(((TreeSet<String>)tSet2).descendingSet());

		/* 반복자(Iterator)를 이용해서 저장된 요소들을 모두 대문자로 변경해서 내림차순 출력 처리 */
		Set<String> tSet3 = new TreeSet<>();
		tSet3.add("apple");
		tSet3.add("banana");
		tSet3.add("peach");
		tSet3.add("watermelon");
		tSet3.add("pineapple");
		
		Iterator<String> dIter = ((TreeSet<String>)tSet3).descendingIterator();
		
		while(dIter.hasNext()) {
			System.out.println(dIter.next().toUpperCase());
		}
	}
}
