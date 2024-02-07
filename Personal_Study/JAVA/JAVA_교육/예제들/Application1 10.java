package com.greedy.section03.api.scanner;

import java.util.Scanner;

public class Application1 {
	public static void main(String[] args) {
		
		/* java.util.Scanner를 이용한 다양한 자료형 값 입력 받기 */
		/* 콘솔 화면에 직접 값을 입력해서 출력 해보기 */
		
		/* 1. Scanner 객체 생성 */			//Scanner를 보이게 하겠다.
		/* 1-1. 원래 Scanner 객체를 생성하기 위한 형태 */
//		java.util.Scanner sc = new java.util.Scanner(java.lang.System.in);
		
		/* 1-2. 하지만 java.lang은 패키지 이름 생략 가능 */
//		java.util.Scanner sc = new java.util.Scanner(System.in);
		
		/* 1-3. 다른 패키지에 있는 클래스 사용 시 패키지명을 생략하기 위해 import를 사용한다. */
		Scanner sc = new Scanner(System.in);
		
		/* 2. 자료형별 값 입력 받기 */
		/* 2-1. 문자열 입력 받기 */
		/* nextLine() : 커서가 깜빡이며 입력을 기다렸다가, 입력 받고 엔터를 누르면 문자열로 반환 */
//		System.out.print("이름을 입력하세요: ");
//		String name = sc.nextLine();
//		System.out.println("입력하신 이름은 " + name + "입니다.");
//		
//		/*2-2. 정수형 값 입력 받기 */
//		/* nextInt() 입력 받은 값을 int형으로 반환 */
//		System.out.println("나이를 숫자로 입력하세요: ");
//		int age = sc.nextInt();
//		System.out.println("입력하신 나이는 " +age+ "세 입니다.");
//		
//		/* nextLong(): 입력 받은 값을 long형으로 반환 */
//		System.out.print("금액을 입력해 주세요: ");
//		long money = sc.nextLong();
//		System.out.println("입력하신 금액은 " + money + "원 입니다.");
//		
		/* 2-3. 실수형 값 입력 받기 */
		/* nextFloat() : 입력받은 값을 float 형으로 반환 */
//		System.out.print("키를 입력해 주세요: ");
//		float height = sc.nextFloat();
//		System.out.println("입력하신 키는 " + height + "cm 입니다.");
//		
//		/* nextDouble() : 입력받은 값을 double형으로 반환 */
//		System.out.print("원하는 실수를 입력해 주세요: ");
//		double doubleNumber = sc.nextDouble();
//		System.out.println("입력하신 실수는 " + doubleNumber + "입니다.");
		
		/* 2-4. 논리형 값 입력 받기 */
		/* nextBoolean(): 입력받은 값을 boolean형으로 반환 */
//		System.out.print("참과 거짓 중에 한 가지를 true 또는 false로 입력해 주세요: ");
//		boolean isTrue = sc.nextBoolean();
//		System.out.println("입력하신 논리 값은 " + isTrue + "입니다.");
		
		/* 2-5. 문자형 값 입력 받기 */
		/*
		 * 아쉽게도 문자를 입력받기 위한 기능은 하나의 메소드로 바로 제공하지 않는다.
		 * 따라서 문자열로 입력을 받고, 입력받은 문자열에서 원하는 순번째 문자를 분리하는 방식으로 사용해야 한다.
		 * java.lang.String에 charAt(int index)를 사용해야 한다.
		 * index를 정수형으로 입력하면 문자열에서 해당 인덴스에 있는 한 문자를 문자형(char)으로
		 * 반환해 주는 기능을 한다.
		 * 
		 * index는 0부터 시작하는 숫자체계이며 컴퓨터에서 주로 사용되는 방식이다.
		 */
//		sc.nextLine();		//버퍼에 남아있는 엔터 제거용(nextLIne이 아닌 메소드들로 입력을 받다가, nextLine이 나올 때 추가할 것.)
		System.out.print("아무 문자나 하나의 문자를 입력해 주세요: ");
		char ch = sc.nextLine().charAt(0);
		System.out.println("입력하신 문자는 " + ch + "입니다.");
		
		
	}

}
