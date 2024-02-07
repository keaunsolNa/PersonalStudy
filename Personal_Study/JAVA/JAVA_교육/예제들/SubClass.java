package com.greedy.section03.overriding;

public class SubClass extends SuperClass {

	/* 오버라이딩 시에는 @Override 어노테이션을 먼저 쓰면서 작성하자. */
	@Override
	public void method(int num1) {}					// 오버라이딩 시 변수명은 상관 없다.

	/* 부모가 가진 메소드 명과 일치하지 않으면 에러 발생(어노테이션에 의해 지적됨) */
//	@Override
//	private void method1(int num1) {}				
	
	/* 부모가 가진 리턴 타입이 변경되면 에러 발생 (어노테이션에 의해 지적됨) */
//	@Override
//	public String method(int num) {return null;}
	
	/* 부모가 가진 매개변수의 갯수나 타입, 순서를 변경하면 에러 발생(어노테이션에 의해 지적됨) */
//	@Override
//	public void method(int num, String str) {}
	
	/* private 메소드는 오버라이딩이 불가능하다. (접근 제한) */
//	@Override
//	private void privateMethod() {}
	
	/* final 메소드는 오버라이딩 불가 (자식 클래스에 의한 변경 불가능)*/
//	@Override
//	public void finalMethod() {}
	
	/* 부모 메소드의 접근제한자와 범위가 같거나 더 넓은 범위로 오버라이딩 가능 */
//	@Override
//	protected void protectedMethod() {}				// 같은 범위로는 가능하다.
	
	@Override
	public void protectedMethod() {} 				// 더 넓은 범위로는 가능하다.
	
//	@Override
//	void protectedMethod() {}						// 더 좁은 범위로는 불가능하다.
	
	/*
	 * 부모 클래스에 final 키워드를 추가하면 상속 자체가 되지 않는다. 
	 * 마지막 클래스로 더 이상의 자식 클래스를 두지 않기 때문이다.
	 * 메소드 역시 마찬가지로, fianl 키워드를 붙이면 상속되지 않는다.  
	 */
	

}
