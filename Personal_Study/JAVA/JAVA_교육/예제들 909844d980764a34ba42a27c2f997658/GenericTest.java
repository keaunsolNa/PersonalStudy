package com.greedy.section01.generic;

public class GenericTest <T> {

	/*
	 * 제네릭 설정은 클래스 선언부 마지막 부분에 다이아몬드 연산자(<>)를 이용하여 작성하게 된다. 
	 * 다이아몬드 연산자 내부에 작성하는 영문자는 관례상 대문자로 작성한다.
	 * 
	 * 다이아몬드 연산자 내부에 작성한 T는 타입변수라고 부른다.
	 * 타입 변수를 자료형 대신 사용한 것인데, 가상으로 존재하는 타입이며 T가 아닌 다른 영문자를
	 * 사용해도 무방하다.
	 * 
	 * 실제 사용할 타입을 타입 변수 자리에 ㅔ맞춰서 넣어주게 되면 같은 알파벳이 적힌 부분이
	 * 결정되게 된다.
	 */
	private T value;

	T getValue() {
		return value;
	}

	void setValue(T value) {
		this.value = value;
	}
	
}
