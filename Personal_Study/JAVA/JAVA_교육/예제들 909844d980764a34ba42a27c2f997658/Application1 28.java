package com.greedy.section02.extend.run;

import com.greedy.section02.extend.Bunny;
import com.greedy.section02.extend.DrunkenBunny;
import com.greedy.section02.extend.Rabbit;
import com.greedy.section02.extend.RabbitFarm;

public class Application1 {
	public static void main(String[] args) {

		/* Animal 타입으로는 제네릭 클래스 인스턴스 생성이 불가능하다. */
//		RabbitFarm<Animal> farm1 = new RabbitFarm<> ();
		
		/* Mammal 타입으로는 제네릭 클래스 인스턴스 생성이 불가능하다. */
//		RabbitFarm<Mammal> farm2 = new RabbitFarm<>();
		
		/* 전혀 다른 타입을 이용해서도 제네릭 클래스 인스턴스 생성이 불가능하다. */
//		RabbitFarm<Snake> farm3 = new RabbitFarm<>();
		
		/* Rabbit 타입이나 Rabbit의 후손 타입으로는 인스턴스 생성이 가능하다. */
		RabbitFarm<Rabbit> farm4 = new RabbitFarm<>();
		RabbitFarm<Bunny> farm5 = new RabbitFarm<>();
		RabbitFarm<DrunkenBunny> farm6 = new RabbitFarm<>();
		
		/* setter를 이용할 때도 올바른 타입의 인스턴스를 인자로 전달해야 한다. */
//		farm4.setAnimal(new Snake());
		
		farm4.setAnimal(new Bunny());
		
//		if(farm4.getAnimal() instanceof Bunny) {
//			((Bunny)farm4.getAnimal()).cry();;
//		}
		
		farm4.getAnimal().cry();	// 제네릭, 상속, 다형성, 오버라이딩, 동적 바인딩을 활용하면 형변환에 크게 신경쓰지 않아도 된다.
		
		farm5.setAnimal(new Bunny());
		farm5.getAnimal().cry();
		
		farm6.setAnimal(new DrunkenBunny());
		farm6.getAnimal().cry();
	}

}
