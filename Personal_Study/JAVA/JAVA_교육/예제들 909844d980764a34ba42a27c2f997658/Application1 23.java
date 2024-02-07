package com.greedy.section02.userexception;

import java.util.Scanner;

import com.greedy.section02.userexception.exception.MoneyNegativException;
import com.greedy.section02.userexception.exception.NotEnouhMoneyException;
import com.greedy.section02.userexception.exception.PriceNegativException;

public class Application1 {
	public static void main(String[] args) {

		/*
		 * 이미 정의되어 있는 Exception의 종류는 굉장히 많다.
		 * 하지만 RuuntimeException의 후손 대부분은 예외처리를 강제화 하지 않았다.
		 * 간단한 조건문 등으로 처리가 가능하기 때문에 강제화 하지 않았다.(즉, UnchekedException)
		 */
		
		/* ArithmeticException : 어떤 숫자를 0으로 나누면 에러가 발생하는 예외 클래스 */
		Scanner sc = new Scanner(System.in);
		System.out.println("제수를 하나의 정수로 입력 하시오: ");			// 제수 : 나누는 수, 피제수 : 나누어 지는 수
		
//		int num = 10;
//		int inputNum = sc.nextInt();				// int를 0으로 나누면 ArithmeticException이 된다.
//		double inputNum = sc.nextDouble();			// double 형으로 나누면 infinity가 출력된다. (무한대)
//		double result = 0;
//		String str = "이다.";
//		
//		result = num / inputNum;
		
//		System.out.printf("%d / %.3f = %.1f%s\n", num, inputNum, result, str);
//		System.out.println(num + "/" + inputNum + " = " + Math.floor(result * 10) /10 + str);
		
		/*
		 * printf의 표현들
		 * %c : 문자
		 * %s : 문자열
		 * %d : 십진수 정수
		 * %f : 실수
		 * %.2f : 소숫점 셋째자리에서 반올림해서 소숫점 이하 둘째자리까지 표현 가능
		 */
		
		int num = 10;
		int inputNum = sc.nextInt();
		int result = 0;
		
		try {
			result = num / inputNum;
			
			System.out.printf("%d / %d = %d\n", num, inputNum, result);
		} catch(ArithmeticException e) {
			System.out.println("0으로 나누었네? ㅋㅋ");
			e.printStackTrace();
			e.getMessage();
			System.out.println(e.getMessage());
		}
		
		System.out.println("여기도 실행되나?");
		
		ExceptionTest et = new ExceptionTest();
		
		try {
//			et.checkEnoughMoney(-50000, 50000);
			et.checkEnoughMoney(50000, -50000);
//			et.checkEnoughMoney(60000, 30000);
//			et.checkEnoughMoney(30000, 610000);
		} /*catch (PriceNegativException e) {
			System.out.println(e.getMessage());
		} catch (MoneyNegativException e) {
			System.out.println(e.getMessage());
		}  catch (NotEnouhMoneyException e) {
			System.out.println(e.getMessage());
		}
*/
		catch(Exception e) {					// Exception만으로도 예외클래스들의 다형성으로 인해 처리 가능
			System.out.println("나는 모든 예외를 처리 가능");
			System.out.println(e.getMessage());
		}
			System.out.println("프로그램을 종료합니다.");
	}
}
