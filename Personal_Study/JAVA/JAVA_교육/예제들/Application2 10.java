package com.greedy.section02.string;

public class Application2 {

	public static void main(String[] args) {

		/*
		 * 문자열 인스턴스를 만드는 방법
		 * 1. "" 리터럴 형태 : 동등한 값을 가지는 인스턴스(equals, hashCode)를
		 * 					단일 인스턴스로 관리한다.
		 * 2. new String("문자열") : 매번 새로운 인스턴스를 생성한다.
		 */
		String str1 = "java";
		String str2 = "java";
		String str3 = new String("java");
		String str4 = new String("java");
		
		/* 주소값 비교를 해보자. */
		System.out.println("str1 == str2: " + (str1 == str2));
		System.out.println("str2 == str3: " + (str2 == str3));
		System.out.println("str3 == str4: " + (str3 == str4));
		
		/* hashCode로 값을 확인해 보자. */
		System.out.println("str1의 hashCode: " + str1.hashCode());
		System.out.println("str2의 hashCode: " + str2.hashCode());
		System.out.println("str3의 hashCode: " + str3.hashCode());
		System.out.println("str4의 hashCode: " + str4.hashCode());
		
		/* hashCode()가 오버라이딩 되어 구분이 안되니 진짜 주소를 찍어보자. */
		System.out.println("str1의 identityHashCode: " + System.identityHashCode(str1));
		System.out.println("str2의 identityHashCode: " + System.identityHashCode(str2));
		System.out.println("str3의 identityHashCode: " + System.identityHashCode(str3));
		System.out.println("str4의 identityHashCode: " + System.identityHashCode(str4));
		
		/*
		 * String은 불변이라는 특징을 가진다.								//여기서의 불면은 String 문자열이 할당된 공간이 문자열이 수정될 때마다 새로이 할당된다는 의미를 가지고 있다.
		 * 기존 문자열에 + 연산을 수행하는 경우 문자열을 추가할 수 있고
		 * 이 때 String은 기존 문자열 인스터스가 아닌 새로운 문자열 인스턴스를 할당한다.
		 */
		str2 += "oracle";
		System.out.println("str1 == str2: " + (str1 == str2));
		
		str3 += "oracle";
		System.out.println("str3에 변화를 준 후 identityHashCode: " + System.identityHashCode(str3));

		/* equals() */
		System.out.println("str2.equals(str3): " + str2.equals(str3));
		System.out.println("str1.equals(str4): " + str1.equals(str4));
		
		/*
		 * String은 equals()와 hashCode()메소드를 둘 다 오버라이딩 되어 있다는 사실을 기억해 두자!
		 * 나중에 컬렉션에서 써먹을 내용이다.
		 */
		
		
		
		
	}
}
