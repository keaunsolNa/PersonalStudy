package com.greedy.section02.string;

import java.util.Arrays;
import java.util.StringTokenizer;

public class Application3 {
	public static void main(String[] args) {
		
		/*split()과 StringTokenizer() */
		
		/* 각 문자열의 의미는 사번/이름/주소/부서를 의미한다. */
		String emp1 = "100/홍길동/서울/영업부";				// 모든 값 존재
		String emp2 = "200/유관순//총무부";				// 주소 없음
		String emp3 = "300/이순신/경기도/";				// 부서 없음
		
		/* 먼저 split을 이용해서 3명의 문자열 정보를 분리해 보자 */
		String[] empArr1 = emp1.split("/");
		String[] empArr2 = emp2.split("/");
		String[] empArr3 = emp3.split("/");

		System.out.println(Arrays.toString(empArr1));
		System.out.println(Arrays.toString(empArr2));
		System.out.println(Arrays.toString(empArr3));
		
		for(int i =0; i<empArr1.length; i++) {
			System.out.println("empArr1[" + i +"]: " + empArr1[i]);
		}
		for(String str : empArr2) {
			System.out.println(str);
		}
		for(int i = 0; i<empArr3.length; i++) {
			System.out.println(empArr3[i]);
		}
		
	
		/*
		 * split 메소드 중에 매개변수가 하나인 것은 마지막 구분자 이후 아무 값이 없으면
		 * 한 칸으로 인지하지 않게 되며 다른 값과 달리 배열의 크기도 한 칸이 작게 된다.
		 */
	
		/* StringTokenizer를 활용해 보자. */						//기존의 커서의 값을 반환하고(토큰이 있으면) 커서를 다음 칸으로 옮긴다.
		StringTokenizer st1 = new StringTokenizer(emp1, "/");
		StringTokenizer st2 = new StringTokenizer(emp2, "/");
		StringTokenizer st3 = new StringTokenizer(emp3, "/");
		
		while(st1.hasMoreTokens()) {
			System.out.println("st1: " + st1.nextToken());
		}

		while(st2.hasMoreTokens()) {
			System.out.println("st2: " + st2.nextToken());
		}
		
		while(st3.hasMoreTokens()) {
			System.out.println("st3: " + st3.nextToken());
		}
		
		String colorStr = "red*oranger#blue/yellow green";
		
		/* split으로 처리 */
//		String[] colors = colorStr.split("*#/ ");
		String[] colors = colorStr.split("[*#/ ]");
		System.out.println(Arrays.toString(colors));
		
		/* StringTokenizer로 처리 */
		StringTokenizer colorStringTokenizer = new StringTokenizer(colorStr, "# /*");
		while(colorStringTokenizer.hasMoreTokens()) {
//			String str =colorStringTokenizer.nextToken();
			System.out.println(colorStringTokenizer.nextToken());
//			System.out.println(str);
		}
			
	}
	
}
