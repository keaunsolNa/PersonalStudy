package com.greedy.section02.userexception;

import com.greedy.section02.userexception.exception.MoneyNegativException;
import com.greedy.section02.userexception.exception.NotEnouhMoneyException;
import com.greedy.section02.userexception.exception.PriceNegativException;

public class Application2 {
	public static void main(String[] args) {
		
		/* multi-catch */
		/*
		 * JDK 1.7에서 추가 된 구문으로
		 * 동일한 레벨의 다른 타입의 예외를 하나의 catch 블럭으로 다룰 수 있다. 
		 */
		ExceptionTest et = new ExceptionTest();
		
		try {
			
			
			/* 3가지의 예외 발생 가능성이 있는 메소드 호출 */
			et.checkEnoughMoney(20000, -30000);
			
		/* 예외 상황별로 catch 블럭을 따로 작성 가능 */
		} catch (NotEnouhMoneyException e) {		//예외 상황별로 catch 블럭을 따로 작성 가능
			
			/*
			 * 예외 클래스명, 예외 발생 위치, 예외 메세지 등을 stack 호출 역순으로
			 * 빨간색 글씨를 이용해서 로그 형태로 출력해 주는 기능
			 */
			e.printStackTrace();
		} catch (PriceNegativException | MoneyNegativException e) {
			System.out.println("입력 값이 음수일 경우 예외 발생!");
			System.out.println(e.getClass() + " 발생!");		// 발생한 예외 객체의 자료형(class 풀 클래스 명)
			System.out.println(e.getMessage());				// 발생한 예외 객체의 메세지
			
		} catch (Exception e) {						// 예외 처리 범위가 클수록 아래쪽에서 처리
			e.printStackTrace();
		} finally {
			
			/* 예외 발생 여부와 상관 없이 실행 할 내용 */
			System.out.println("finally 블럭이 내용이 동작함...");
		}
		
		System.out.println("프로그램을 종료합니다.");

		
		
	}
}