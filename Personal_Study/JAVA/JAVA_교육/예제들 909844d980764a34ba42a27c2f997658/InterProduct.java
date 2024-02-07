package com.greedy.section03.interfaceimplements;

public interface InterProduct {

	/* 인터페이스는 public static 상수 필드만 작성이 가능하다. */
	public static final String NAME = "asdfsa";
	public static final int MAX_NUM = 10;

	/* public static 상수 필드만을 가질 수 있기 때문에 모든 필드는 묵시적으로 public static final 이다. */
	int MIN_NUM = 5;

	/* 인터페이스는 생성자를 가질 수 없다. */
//	public InterProoduct() {}					// 컴파일 에러 발생함.
	
	/* 추상 메소드는 작성이 가능하다. */
	public abstract void nonStaticMethod();
	
	/* 인터페이스 안에 작성한 메소드는 묵시적으로 public abstract의 의미를 가진다. */
	void abstractMethod();
	
}
