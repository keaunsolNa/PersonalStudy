package com.greedy.section03.stringbuilder;

public class Application1 {
	public static void main(String[] args) {
		
		/* StringBuilder와 StringBuffer */
		
		/*
		 * StringBuilder : 스레드 동기화 기능 제공되지 않음
		 * StringBuffer : 스레드 동기화 기능 제공, 성능면에서 StringBuilder보다 느림
		 */
		
		/*
		 * String과 StringBuilder
		 * String : 불변이라는 특징을 가지고 있다.
		 * 			문자열에 + 연산으로 합치기 하는 경우, 기존 인스턴스를 수행하는 것이 아닌,
		 * 			새로운 인스턴스를 반환한다.
		 * 			따아서 문자열 변경이 자주 일어나는 경우 성능 면에서 좋지 않다.
		 * 			하지만 변하지 않는 문자열을 자주 읽어들이는 경우에는 오히려 좋은 성능을
		 * 			기대할 수 있다.
		 * 
		 * StringBuilder : 가변이라는 특징을 가지고 있다.
		 * 					문자열에 append() 메소드를 이용하여 합치기 하는 경우
		 * 					기본 인스턴스를 수정하기 때문에 새로운 인스턴스를 생성하지 않는다.
		 * 					따라서 잦은 문자열 변경이 일어나는 경우 String보다 성능이 좋다.
		 */
		
		/* StringBuilder 인스턴스 생성 */
		StringBuilder sb = new StringBuilder("java");
//		StringBuilder sb2 = "java";			// String처럼 문자열 리터럴 값으로 인스턴스 생성이 안된다.

		/* StringBuilder는 toString이 오버라이딩 되어 있다. */
		System.out.println(sb.toString());
		
		/* hashCode는 오버라이딩 되어 있지 않다. */
		System.out.println("sb의 hashcode: " + sb.hashCode() + " " + System.identityHashCode(sb));
		
		/* 문자열 누적 */
//		sb += "oracle";						// StringBuilder와 String을 직접 더할 순 없다.
		sb.append("oracle");				// append 메소드를 사용해야 누적 효과를 줄 수 있다.
		
		System.out.println("sb를 수정후 주소값: " + sb.hashCode());
		System.out.println(sb);
	}

}
