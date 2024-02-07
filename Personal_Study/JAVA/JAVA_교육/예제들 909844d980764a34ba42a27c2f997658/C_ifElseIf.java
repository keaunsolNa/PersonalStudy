package com.greedy.section01.conditional_statment;

import java.util.Scanner;

public class C_ifElseIf {

	public static void testSimpleIfElseIfStatement() {
		
		/*
		 * if -else-if 문은 조건식 1의 결과 값이 참(true)이면 if{ } 안에 있는 코드를 실행하고
		 * 조건식 1이 false이면 조건식 2를 확인하여 참(true)이면 else if{ } 안에 있는 코드를 실행한다.
		 * 조건식 1, 2의 결과 값이 모두 거짓(false)이면 else{ } 안에 있는 코드를 실행한다.
		 * 여러 개의 조건을 제시하여 그 중 한 가지를 반드시 실행시키고 싶은 경우 if, else if, else를 모두 
		 * 사용한다.
		 */
		
		/* 금도끼 은도끼 동화에서 산신령이 어떤 도끼가 나무꾼의 도끼인지를 물어보는 시나리오로 코드를 작성하고 실행하자. */
		
		System.out.println("산 속에서 나무를 하던 나무꾼이 연못에 도끼를 빠뜨리고 말았다.");
		System.out.println("연못에서 산신령이 나타나 금도끼, 은도끼, 쇠도끼를 들고 나타났다.");
		System.out.println("나무꾼에게 셋 중 어떤 도끼가 나무꾼의 도끼인지 물어보았다.");
		
		System.out.println("어느 도끼가 너의 도끼냐? (1. 금도끼, 2. 은도끼, 3. 쇠도끼): ");
		
		Scanner sc = new Scanner(System.in);
		int answer = sc.nextInt();
		
		if (answer == 1) {
			System.out.println("이런 거짓말 쟁이!! 너에게는 아무런 도끼도 줄 수가 없구나!! 이 욕심쟁이야!!");
		}
		else if (answer == 2) {
			System.out.println("욕심이 과하지는 않지만 그래도 넌 거짓말을 하고 있구나!! 어서 썩 사라지거라!!");
		}
		else{
			System.out.println("오호~ 정직하구나~ 여기 있는 금도끼, 은도끼, 쇠도끼를 다 가져가거라!!");
		}
		
		System.out.println("그렇게 산신령은 다시 연못 속으로 사라지고 말았다...");
	}
	
	public static void testNestedIfElseIfStatement() {
		
		/* 중첩된 if else-if 문 실행흐름 확인 */
		
		/*
		 * greedy 대학의 김XX교수님은 학생들 시험 성적을 수기로 계산해서 학점 등급을 매기는 채점 방식을
		 * 사용하고 있다.
		 * 90점 이상이면 'A', 80점 이상이면 'B', 70점 이상이면 'C', 60점 이상이면 'D', 60점 미만인
		 * 경우에는 'F'를 학점 등급으로 하는 기준이다. 추가로 각 등급의 중간점수(96, 85, 75...) 이상인
		 * 경우 '+'를 붙여서 등급을 세분화 할 수 있게 학생의 이름과 점수를 입력하면 자동으로 학점 등급이
		 * 계산되는 기능을 만들자.
		 */
		
		
//		만약에(입력받은 점수가 90점 이상이면){
//			'A'학점 부여
//		} 그렇지 않고(입력받은 점수가 80점 이상이면) {
//			'B' 학점 부여
//		} 그렇지 않고(입력받은 점수가 70점 이상이면) {
//			'C' 학점 부여
//		} 그렇지 않고(입력받은 점수가 60점 이상이면) {
//			'D' 학점 부여
//		} 그렇지 않으면 {
//			'F' 학점 부여
//		}
		
		Scanner sc = new Scanner(System.in);
		System.out.println("학생의 이름을 입력하세요: ");
		String name = sc.nextLine();
		System.out.println(name + "학생의 점수를 입력하세요: ");
		int point = sc.nextInt();
		
		String grade = "";
		if (point >= 90) {
			grade = "A";
			if(point >= 95) {
				grade += "+";				
			}
		} else if(point >= 80) {
			grade = "B";
			if(point >= 85) {
				grade += "+";				
			}
		} else if(point >= 70) {
			grade = "C";
			if(point >= 75) {
				grade += "+";				
			}
		} else if(point >= 60) {
			grade = "D";
			if(point >= 65) {
				grade += "+";				
			}
		} else {
			grade = "F";
		}
		// char가 아닌 String 형을 사용하는 이유는 A+의 경우, A의 유니코드값과 +의 유니코드 값을 더한 숫자가 되기 때문이다.
		System.out.println("이름: " + name + ", 학점: " + grade);
	}
	
	public static void improveNastedIfElseIfStatment() {
		
		/* testNestedIfElseIfStatment() 메소드와 같은 문제를 좀 더 짧은 코드로 풀어보자. (완성도를 높여보자) */
		
		Scanner sc = new Scanner(System.in);
		System.out.println("학생의 이름을 입력하세요: ");
		String name = sc.nextLine();
		System.out.println("학생의 점수를 입력하세요: ");
		int point = sc.nextInt();
		
		String grade = "";
		
		if(point > 100 || point < 0) {							// 0보다 작거나 100점을 초과해서 입력했을 경우
			System.out.println("성적 입력 범위를 벗어났습니다!!");
		} else {												// 점수 입력 정상 범위일 때
			if(point >= 90) {
				grade = "A";
			} else if(point >= 80) {
				grade = "B";
			} else if(point >=70) {
				grade = "C";
			} else if(point >=60) {
				grade = "D";
			} else {
				grade = "F";
			}
			if(point % 10 >= 5 && point >= 60 || point == 100) {
				grade += "+";
			}
			System.out.println("이름: " + name + ", 학점: " + grade);
		}
		
	}
}
