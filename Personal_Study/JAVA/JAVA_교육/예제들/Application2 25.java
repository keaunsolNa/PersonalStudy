package com.greedy.section02.extend.run;

import com.greedy.section02.extend.Bunny;
import com.greedy.section02.extend.DrunkenBunny;
import com.greedy.section02.extend.Rabbit;
import com.greedy.section02.extend.RabbitFarm;
import com.greedy.section02.extend.wildCardFarm;

public class Application2 {
	public static void main(String[] args) {

		wildCardFarm wildCardFarm = new wildCardFarm();
		
		/* 농장 생성 자체가 불가능한 것은 매개변수로 사용할 수 없다. */
//		wildCardFarm.anyType(new RabbitFarm<Mammal>(new Mammal()));
//		wildCardFarm.anyType(new RabbitFarm<reptile>(new Reptile()));

		/* <?> : 제한 없음 (제네릭 클래스의 인스턴스라면 다 받아 줄 수 있다.) */
		wildCardFarm.anyType(new RabbitFarm<Rabbit>(new Rabbit()));
		wildCardFarm.anyType(new RabbitFarm<Bunny>(new Bunny()));
		wildCardFarm.anyType(new RabbitFarm<DrunkenBunny>(new DrunkenBunny()));
		
		/* 
		 * <? extends Type> : 와일드카드의 상한 제한
		 * 						(Type과 Type의 후손을 이용해 생성된 인스턴스만 인자로 사용 가능)
		 */
//		wildCardFarm.extendsType(new RabbitFarm<Rabbit>(new Rabbit()));
		wildCardFarm.extendsType(new RabbitFarm<Bunny>(new Bunny()));
//		wildCardFarm.extendsType(new RabbitFarm<DrunkenBunny>(new DrunkenBunny()));
		
		/* 
		 * <? super Type> : 와일드카드의 하한 제한
		 * 					(Type과 Type의 부모를 이용해 생성한 인스턴스만 인자로 사용 가능)
		 */
		wildCardFarm.superType(new RabbitFarm<Rabbit>(new Rabbit()));
		wildCardFarm.superType(new RabbitFarm<Bunny>(new Bunny()));
//		wildCardFarm.superType(new RabbitFarm<DrunkenBunny>(new DrunkenBunny()));
		
		/*
		 * 너무 어렵거나 햇갈리면 와일드카드는 이런식으로 메소드의 매개변수로 넘어오는 인스턴스에 대해
		 * 제한을 거는 것이라고만 봐두고 나중에 실무에서 필요하면 보자. 
		 */
		
		
	}

}
