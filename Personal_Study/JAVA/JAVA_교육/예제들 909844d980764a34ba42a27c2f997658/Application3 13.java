package com.greedy.section01.polymorphism;

public class Application3 {
	public static void main(String[] args) {
		
		/* 매개변수에도 다형성을 적용할 수 있다. */
		Application3 app3 = new Application3();

		Animal animal1 = new Rabbit();
		Animal animal2 = new Tiger();
		
		app3.feed(animal1);
		app3.feed(animal2);
		
		app3.feed2((Rabbit)animal1);					// 명시적 형변환이 필요하다.
		app3.feed3((Tiger)animal2);						// 명시적 형변환이 필요하다.
		
		app3.feed4(animal1);
		app3.feed4(animal2);
		
	}
	
	public void feed(Animal animal) {

	}
	
	public void feed2(Rabbit rabbit) {
		
	}
	
	public void feed3(Tiger tiger) {
		
	}
	
	public void feed4(Object obj) {
		
	}
}
