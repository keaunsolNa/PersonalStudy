package com.greedy.section01.thread;

public class Plane implements Runnable{

	/* Runnable 인터페이스 구현 */
	/*
	 * 자바는 단일 상속만 지원하기 때문에 Thread 클래스를 상속받기 힘든 경우
	 * 인터페이스를 상속받아 구현한다.
	 */
	@Override
	public void run() {
		for(int i = 0; i < 1000; i++) {
			System.out.println("palne fly...");

			try {
				Thread.sleep(1);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	
}
