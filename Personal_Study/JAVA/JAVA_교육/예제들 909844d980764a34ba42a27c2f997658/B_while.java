package com.greedy.section02.looping_statement;

import java.util.Scanner;

public class B_while {
	public void testSimpleWhileStatement() {
		
		/* 반복문의 기본 흐름 테스트 */
		
		/* 1부터 10까지 1씩 증가시키면서 i값을 출력하는 기본 반복문 */
		int i = 1;
		while(i <= 10) {
			System.out.println(i + "출력");
			i++;
		}
		
		/* 무한반복을 돌리기 쉽다. */
//		while(true) {
//			
//		}
	}
		public void testWhileExample1() {
			
			/* 입력한 문자열의 인덱스를 이용하여 문자 하나씩 출력해 보자. */
			Scanner sc = new Scanner(System.in);
			System.out.println("문자열 입력: ");
			String str = sc.nextLine();
			
			/* charAt(): 문자열에서 인덱스에 해당하는 문자를 char형으로 반환하는 기능*/
			/* length() : String 클래스의 메소드로 문자열의 길이를 int형으로 반환하는 기능 */
			
//			System.out.println(str.charAt(1));
//			System.out.println(str.length());
			
			System.out.println("======= for문 =======");
			for(int i = 0; i < str.length(); i++) {
				char ch = str.charAt(i);
				System.out.println(i + "번째 인덱스: " + ch);
			}
			
			System.out.println("======= while문 =======");
			int i = 0;
			while(i <str.length()) {
				char ch = str.charAt(i);
				System.out.println(i + "번째 인덱스: " + ch);
				i++;
			}
		}
		
		public void testWhileExample2() {
			
			/* 정수 하나를 입력받아 1부터 입력받은 정수까지의 합계를 구하자. */

			Scanner sc = new Scanner(System.in);
			System.out.println("숫자를 입력하세요");
			int inputNum = sc.nextInt();
			
			int sum = 0;
			
			for(int i=0; i<inputNum;i++) {
				sum += (i+1);
				
			}
		
			System.out.println("1부터 입력받은 정수까지의 합계 : " +sum);

			int i = 0;
			while(i<inputNum) {
				sum += (i+1);
				i++;
			}
				
				
		
			System.out.println("1부터 입력받은 정수까지의 합계 : " +sum);
			
		}
	}

