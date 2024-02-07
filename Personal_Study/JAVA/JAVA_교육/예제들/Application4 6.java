package com.greedy.section01.polymorphism;

public class Application4 {
	public static void main(String[] args) {

		/* 리턴 타입에도 다형성을 적용할 수 있다. */
		Animal randomAnimal = new Application4().getRandomAnimal();
		randomAnimal.cry();
	}
	
	public Animal getRandomAnimal() {
		
		int random = (int)(Math.random() * 2);
		
		return random == 0 ? new Rabbit() : new Tiger();
	}
}
