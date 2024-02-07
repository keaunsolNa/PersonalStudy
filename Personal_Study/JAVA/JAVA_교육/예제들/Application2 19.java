package com.greedy.section01.polymorphism;

public class Application2 {
	public static void main(String[] args) {
		
		/* 다형성과 객체 배열을 이용해서 하위 타입의 여러 인스턴스를 연속 처리할 수 있다. */
		
		/* 상위 타입의 레퍼런스 배열을 만들고 각 인덱스에 하위 타입의 인스턴스들을 생성해서 대입한다. */
		Animal[] animals = new Animal[5];
		
		animals[0] = new Rabbit();
		animals[1] = new Tiger();
		animals[2] = new Rabbit();
		animals[3] = new Tiger();
		animals[4] = new Rabbit();
		
		/* Animal 클래스가 가지는 메소드를 오버라이딩 메소드 호출 시 동적 바인딩을 활용할 수 있다. */
		/*
		 * 토끼 울어라! 호랑이 울어라! 이렇게 하는게 아니고
		 * 동물들 다 울어라! 이런 느낌으로 코딩을 하면 된다. (동일한 메세지 송신 가능)
		 */
		for(int i = 0; i < animals.length; i++) {
			animals[i].cry();
		}
		
		/* 동물들아 너가 만약 토끼면 점프를 뛰고 호랑이면 물어라! 하는 느낌으로 코딩을 하면 된다. */
		for(int i = 0; i < animals.length; i++) {
			if(animals[i] instanceof Rabbit) {
				((Rabbit)animals[i]).jump();
			} else if (animals[i] instanceof Tiger ) {
				((Tiger)animals[i]).bite();
			} else {
				System.out.println("이 인스턴스는 호랑이나 토끼가 아닙니다.");
			}
		}
		
		
	}
}
