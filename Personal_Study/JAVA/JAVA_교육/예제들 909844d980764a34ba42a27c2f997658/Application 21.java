package com.greedy.section01.thread;

public class Application {
	public static void main(String[] args) {
		
		/* 인스턴스 생성 */
		Car car = new Car();
		Tank tank = new Tank();
		Plane plane = new Plane();
		
		/* Thread 타입의 인스턴스로 변환(다형성 적용) */
		Thread t1 = new Car();
		Thread t2 = new Tank();
		Thread t3 = new Thread(plane);
		
		/* 스레드는 기본적으로 1~10의 우선순위 중 5의 우선순위를 가지고 있다. 
		 * (처리를 위한 시간 할당을 우선순위가 높을 수록 많이 부여한다.) */
		System.out.println("t1의 우선순위: " + t1.getPriority());		// 5
		System.out.println("t2의 우선순위: " + t2.getPriority());		// 5
		System.out.println("t3의 우선순위: " + t3.getPriority());		// 5
		
		t1.setPriority(Thread.MAX_PRIORITY);						// 10
		t2.setPriority(Thread.MIN_PRIORITY);						// 1
		
		System.out.println("t1의 우선순위: " + t1.getPriority());		// 10
		System.out.println("t2의 우선순위: " + t2.getPriority());		// 5
		System.out.println("t3의 우선순위: " + t3.getPriority());		// 1
		
		/*
		 * run() 메소드를 호출하면 다른 스레드가 아닌 메인 스레드에서 메소드를 호출하는
		 * 방식으로 동작한다. (메인 스레드 하나만 존재)
		 */
//		t1.run();

		/*
		 *  스레드를 메인스레드나 다른 스레드와 별개로 따로 동작시키기 위해서는 start()를 호출한다. 
		 *  start() 메소드는 스레드 인스턴스의 run() 메소드를 자동 호출한다. 
		 */ 
		t1.start();
		t2.start();
		t3.start();

		/* join() 메소드를 지정한 스레드가 종료될 때까지 나머지 스레드들의 종료를 대기 시킨다. */
		try {
			t1.join();
			t2.join();
			t3.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
		System.out.println("---------- main end!!");
	}
}
