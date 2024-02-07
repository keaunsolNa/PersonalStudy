package com.greedy.section01.erxtend;

public class FireCar extends Car{

	public FireCar () {
		super();
		System.out.println("FireCare 클래스의 기본 생성자 호출됨...");
	}

	/* 소방차는 추가적으로 물 뿌리는 기능을 수행할 수 있다. */
	public void sprayWater() {
		System.out.println("불난 곳을 발견했습니다. 물을 뿌립니다. ============33");
	}
}
