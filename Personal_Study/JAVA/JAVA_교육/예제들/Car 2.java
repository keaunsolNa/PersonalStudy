package com.greedy.section01.erxtend;

public class Car{

	/* 자동차의 달리는 상태를 확인할 수 있는 필드 */
	private boolean isRunning;
	
	public Car() {
		super();
		System.out.println("Car 클래스의 기본 생성자 호출됨...");
	}
	
	public boolean getIsRunning() {
		return this.isRunning;
	}
	
	public void soundHorn() {
		if(isRunning) {								// 달릴 때 경적 울릴 시
			System.out.println("빵! 빵!");
		} else {									// 달리지 않을 때 경적 울릴 시
			System.out.println("주행 중이 아닌 상태에서는 경적을 올릴 수 없습니다.");
		}
	}
	
	public void run() {
		isRunning = true;
		System.out.println("자동차가 달립니다.");
	}
	
	public void stop() {
		isRunning = false;
		System.out.println("자동차가 멈춥니다.");
	}
}
