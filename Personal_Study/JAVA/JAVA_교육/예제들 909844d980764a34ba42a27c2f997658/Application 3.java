package com.greedy.section01.init;

public class Application {

	public static void main(String[] args) {
		Car car1 = new Car("페라리", 300);
		Car car2 = new Car("람보르기니", 350);
		Car car3 = new Car("롤스로이스", 250);
		Car car4 = new Car("부가티베이론", 400);
		Car car5 = new Car("포터", 500);
		
		car1.driverMaxSpeed();
		car2.driverMaxSpeed();
		car3.driverMaxSpeed();
		car4.driverMaxSpeed();
		car5.driverMaxSpeed();
		
		/* 동일한 타입의 인스턴스를 여러개 사용해야 할 때 객체 배열을 사용하면 보다 효율적이다. */
		Car[] carArray = new Car[5];
		
		carArray[0] = new Car("페라리", 300);
		carArray[1] = new Car("람보르기니", 350);
		carArray[2] = new Car("롤스로이스", 250);
		carArray[3] = new Car("부가티베이론", 400);
		carArray[4] = new Car("포터", 500);
		
		for(int i =0; i < carArray.length; i++) {
			carArray[i].driverMaxSpeed();
		}
		
		/* 객체 배열도 할당과 동시에 초기화 할 수 있다. */
		Car[] carArray2 = {
				new Car("페라리", 300),
				new Car("람보르기니", 350),
				new Car("롤스로이스", 250),
				new Car("부가티베이론", 400),
				new Car("포터", 500)
		};
		
		for(Car c : carArray2) {
			c.driverMaxSpeed();
		}
	}

}
