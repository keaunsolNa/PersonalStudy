package com.greedy.section01.erxtend;

public class RacingCar extends Car{

	public RacingCar() {
		System.out.println("RacingCar 클래스의 기본 생성자 호출됨...");
	}
	
	@Override
	/*
	 * @Override를 하는 이유(@는 어노테이션이다.)
	 * 1. 가독성(오버라이딩 된 메소드임을 한 눈에 알 수 있다.)
	 * 2. 오타방지(부모의 메소드와 다르게 작성 시 오타 확인을 컴파일 에러로 확인할 수 있다.)
	 */
	public void run() {
		System.out.println("레이싱카가 전속력으로 멋지게 질주합니다!!");
	}
	
	@Override
	public void soundHorn() {
		System.out.println("레이싱카는 경적을 울리지 않습니다. (조용...)");
	}
	
	@Override
	public void stop() {
		System.out.println("레이싱카가 멈춥니다.");
	}
}
