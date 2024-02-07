package com.greedy.section02.extend;

public class wildCardFarm {

	/* 
	 * 와일드 카드(?)란?
	 * 제네릭 클래스의 객체를 메소드의 매개변수로 사용 시 객체의 타입변수를 제한하는 것
	 */
	/* 와일드 카드로 농장 제네릭 객체들에 대해 제한을 걸어보자. */
	
	/* 어떤 토끼가 있는 농장이던 토끼농장은 다 받아 주겠다 */
	public void anyType(RabbitFarm<?> farm) {
		farm.getAnimal().cry();
	}
	
	/* 토끼가 Bunny이거나 그 후손 타입만 존재하는 토끼 농장만 매개변수로 받아 주겠다. */
	public void extendsType(RabbitFarm<? extends Bunny> farm) {
		farm.getAnimal().cry();
	}
	
	/* 토끼가 Bunny이거나 그 부모 타입만 존재하느 ㄴ토끼 농장만 매개변수로 받아 주겠다. */
	public void superType(RabbitFarm<? super Bunny> farm) {
		farm.getAnimal().cry();
	}
}
