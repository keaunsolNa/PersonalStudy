package com.greedy.section01.exception;

public class ExceptionTest {

	public void checkEnoughMoney(int price, int money) throws Exception   {
		System.out.println("가지고 계신 돈은 " + money + "원 입니다.");
		
		if(money >= price) {
			System.out.println("상품을 구입하기 위한 금액이 충분합니다.");
		} else {
//			System.out.println("상품을 구입하기 위한 금액이 충분하지 않습니다.");
			
			/* 강제로 예외 발생 */
			throw new Exception();			// 예외 클래스 타입의 인스턴스를 throw
		}
		System.out.println("즐거운 쇼핑 되세요~");
	}
}
