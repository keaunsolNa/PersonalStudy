package com.greedy.section01.exception;

public class Application2 {
	public static void main(String[] args) {
		ExceptionTest et = new ExceptionTest();
		
		try {
			/* 예외발생 가능성이 있는 메소드(throws로 처리 된 메소드)가 try 블럭 안에서 호출된다. */
			/* 상품 가격은 50000원 이지만, 가진 돈은 10000원인 경우 */
			et.checkEnoughMoney(50000, 10000);	
			
			/* 위의 전달인자로 메소드 호출 시에는 예외가 발생하므로 try블럭의 이후 부분은 실행되지 않는다. */
			System.out.println("================= 상품 구입 가능 ================");
		} catch(Exception e) {
			
			/* 예외가 발생하는 경우 catch 블럭의 코드를 실행한다. */
//			System.out.println("catch 실행 됨");
			System.out.println("================== 상품 구입 불가능 ==============");
		}
		
		System.out.println("프로그램을 종료합니다.");
	}
}
