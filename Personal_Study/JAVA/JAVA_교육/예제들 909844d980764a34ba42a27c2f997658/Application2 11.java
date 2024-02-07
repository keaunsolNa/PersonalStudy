package com.greedy.section03.stringbuilder;

public class Application2 {
	public static void main(String[] args) {

		/* StringBuilder의 자주 사용되는 메소드 */
		/* StringBuilder를 StringBuffer로 바꿔서 문제없이 돌아가는 것을 확인하고 실제 메소드 기능은 같다는 것을 이해하자. */
		 
		
		/* StringBuilder 기본 생성자로 인스턴스 생성 */
//		StringBuilder sb1 = new StringBuilder();
		StringBuffer sb1 = new StringBuffer();
		
		/* capacity(): 용량(현재 버퍼의 크기)를 정수형으로 반환하는 메소드 */
		System.out.println(sb1.capacity());
		
		/*
		 * append(): 인자로 전달된 값을 문자열로 변환 후 기존 문자열의 마지막에 추가한다.
		 * 			 기본 올림을 초과하는 경우 (기존 용량 + 1) * 2를 하여 용량을 확장시킨다.
		 */
		for(int i = 0; i < 50; i++) {
			sb1.append(i);
			
			System.out.println("sb1: " + sb1);
			System.out.println("capacity: " + sb1.capacity());		// 2n +2씩 증가
			System.out.println("hashCode: " + sb1.hashCode());		// 동일한 인스턴스
		}
		
		/* 새로운 StringBuilder 인스턴스 생성 */
//		StringBuilder sb2 = new StringBuilder("javaoracle");
		StringBuffer sb2 = new StringBuffer("javaoracle");
		System.out.println(sb2.capacity());
	
		/*
		 * delete() : 시작인덱스와 종료 인덱스를 이용해서 문자열에서 원하는 부분의 문자열을 제거한다.
		 * deleteCharAt(): 문자열 인덱스를 이용해서 문자 하나를 제거한다.
		 * 둘 다 원본에 영향을 미친다.
		 */
		System.out.println("delete(): " + sb2.delete(2, 5));			// jaracle
		System.out.println("deleteCharAt(): " + sb2.deleteCharAt(0));	// aracle

		System.out.println("sb2: " + sb2);								// aracle
		
		/*
		 * insert() : 인자로 전달된 값을 문자열로 변환 후 지정한 인덱스 위치에 추가한다.
		 * 원본에 영향을 미친다.
		 */
		System.out.println("insert() : " + sb2.insert(1, "vao"));		// avaoracle
		System.out.println("insert() : " + sb2.insert(0, "j"));			// javaoracle
		System.out.println("insert() : " + sb2.insert(sb2.length(),  "jdbc"));			

		System.out.println("sb2: " + sb2);
		
		/*
		 * reverse() : 문자열 인덱스 순번을 역순으로 재배열한다.
		 * 원본에 영향을 미친다.
		 */
		System.out.println("reverse() : " + sb2.reverse());
		
		System.out.println("sb2: " + sb2);
	}
}
