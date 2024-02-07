package com.greedy.section01.thread;

public class Car extends Thread{

	/* Thread 클래스 상속받아 구현 */
	
	@Override
	public void run() {
		for(int i = 0; i < 1000; i++) {
			System.out.println("Car driving...");
			
			try {
				
				/* 스레드를 지연시키는 sleep 메소드(밀리초 단위) */
				Thread.sleep(1);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
}
