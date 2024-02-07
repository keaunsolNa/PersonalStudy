package com.greedy.section04.override;

import java.io.FileNotFoundException;

public class SubClass extends SuperClass{

	/* 예외를 던지는 메소드를 오버라이딩 해 보자. */
	
//	@Override
//	public void method() {}									// 정상. 
	
	/* 같은 예외를 던져주는 구문으로 오버라이딩은 가능하다. */
//	@Override
//	public void method() throws IOException {}				// 정상
	
	/* 부모가 던지는 예외보다 상위의 예외로는 자식 클래스에서 오버라이딩 할 수 없다. */
//	@Override
//	public void method() throws Exception {}				// 컴파일 에러 발생

	/*부모가 던지는 예외보다 하위의 예외로는 오버라dl딩 할 수 있다. */
	@Override
	public void method() throws FileNotFoundException {}	// 정상
	
}
