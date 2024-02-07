package com.greedy.section02.userexception;

import com.greedy.section02.userexception.exception.MoneyNegativException;
import com.greedy.section02.userexception.exception.NotEnouhMoneyException;
import com.greedy.section02.userexception.exception.PriceNegativException;

public class ExceptionTest {
	public void checkEnoughMoney(int price, int money) throws PriceNegativException, MoneyNegativException, NotEnouhMoneyException {
//		public void checkEnoughMoney(int price, int money) throws Exception {
		
		/* 상품 가격이 음수인지 확인하고, 음수인 경우 예외를 발생시킬 것이다. */
		if(price < 0) {
			throw new PriceNegativException("상품 가격은 음수일 수 없습니다.");
		}
		
		/* 가진 돈도 음수인지 확인하고, 음수인 경우 예외를 발생시킬 것이다. */
		if(money < 0) {
			throw new MoneyNegativException("가진 돈은 음수일 수 없습니다.");
		}
		
		/* 위의 두 값이 양수로 정상 입력 되었더라도 상품 가격이 가진 돈보다 더 큰 경우 예외를 발생시킬 것이다. */
		if(price > money) {
			throw new NotEnouhMoneyException("가진 돈보다 상품 가격이 비쌉니다.");
		}
	
		System.out.println("가진 돈이 충분합니다. 즐거운 쇼핑 되세요~");
	}
}
