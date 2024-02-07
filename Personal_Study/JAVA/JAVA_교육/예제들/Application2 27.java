package com.greedy.section02.set.run;

import java.util.LinkedHashSet;
import java.util.Set;

public class Application2 {
	public static void main(String[] args) {
		
		/* LinkedHashSet 클래스 */
		/*
		 * HashSet이 가지는 기능을 모두 가지고 있고
		 * 추가적으로 저장 순서를 유지하는 특징을 가지고 있다.
		 * JDK 1.4부터 제공하고 있다.
		 */
		
		Set<String> lhSet = new LinkedHashSet<>();
		
		lhSet.add("java");
		lhSet.add("orcle");
		lhSet.add("jdbc");
		lhSet.add("html");
		lhSet.add("css");
		
		System.out.println("lhSet : " + lhSet);		// 넣은 순서 유지되는 것 확인.
	}
}
