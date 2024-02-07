package com.greedy.section02.abstractclass;

public class SmartPhone extends Product{

	@Override
	public void abstractMethod() {
		System.out.println("Product 클래스의 abstractMethod를 오버라이딩 한 메소드 호출함..");
	}
}
