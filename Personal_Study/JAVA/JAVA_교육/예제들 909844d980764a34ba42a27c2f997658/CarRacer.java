package com.greedy.section03.abstraction.dto;

public class CarRacer {

	Car car = new Car();
	
	public void startUp() {
		car.startUP();
	}

	public void stepAccelator() {
		car.go();
	}

	public void stepBreak() {
		car.stop();
	}

	public void trunOff() {
		car.trunOff();
	}
		
}
