package com.greedy.section02.abstractclass;

public abstract class Product {

	/* 추상클래스도 필드를 가질 수 있다. */
	private int nonStaticField;
	private static int staticField;
	
	/* 
	 * 추상클래스는 생성자도 가질 수 있다.
	 * 하지만 직접적으로 생성자를 통해 인스턴스를 생성할 수는 없다.
	 */
	public Product() {}
	
	/* 추상클래스는 일반적인 메소드도 가질 수 있다. */
	public void nonStaticMethod() {
		System.out.println("Product 클래스의 nonStaticMethod 호출함...");
	}
	
	public static void staticMethod() {
		System.out.println("Product 클래스의 staticMEthod 호출함...");
	}
	
	/* 추상 메소드가 하나라도 있다면 해당 클래스는 반드시 추상 클래스여야 한다. */
	public abstract void abstractMethod();
	
	
	
}
