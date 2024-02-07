package com.greedy.section04.practice1;

public class MoveRun {
	public static void main(String[] args) {
		Heart1 h1 = new Heart1();
		Star1 s1 = new Star1();
		
		Thread th1 = new Thread(h1);
		Thread ts1 = new Thread(s1);
		
		try {
			th1.start();
			Thread.sleep(500);
			ts1.start();
			
			th1.join();
			ts1.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
}
